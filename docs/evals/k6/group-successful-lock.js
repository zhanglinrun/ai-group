import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const concurrency = Number(__ENV.CONCURRENCY || 20);
const warmupSeconds = Number(__ENV.WARMUP_SECONDS || 120);
const steadySeconds = Number(__ENV.STEADY_SECONDS || 600);
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8091';
const internalToken = __ENV.INTERNAL_TOKEN || '';
const activityId = Number(__ENV.ACTIVITY_ID || 199901);
const goodsId = __ENV.GOODS_ID || '9890002';
const orderPrice = Number(__ENV.ORDER_PRICE || 12);
const runId = __ENV.RUN_ID || 'local01';
const source = `p${runId}`.slice(0, 8);
const channel = 'load';
const lockMaxAttempts = Math.max(1, Number(__ENV.LOCK_MAX_ATTEMPTS || 3));
const retryBackoffMillis = Math.max(0, Number(__ENV.RETRY_BACKOFF_MILLIS || 100));
const requestTimeoutSeconds = Math.max(1, Number(__ENV.REQUEST_TIMEOUT_SECONDS || 30));
const gracefulStopSeconds = (lockMaxAttempts * requestTimeoutSeconds * 2)
  + Math.ceil(((lockMaxAttempts - 1) * retryBackoffMillis) / 1000)
  + 5;

const businessSuccess = new Rate('lock_business_success');
const businessFailure = new Rate('lock_business_failure');
const transportFailure = new Rate('lock_transport_failure');
const lockAttempts = new Counter('lock_attempts');
const queryAttempts = new Counter('lock_result_query_attempts');
const ambiguousOutcomes = new Counter('lock_ambiguous_outcomes');
const queryRecoveries = new Counter('lock_query_recoveries');
const retryRecoveries = new Counter('lock_retry_recoveries');
const queryTransportFailures = new Counter('lock_result_query_transport_failures');
const transportConnectionRefused = new Counter('lock_transport_connection_refused');
const transportTimeout = new Counter('lock_transport_timeout');
const transportHttpError = new Counter('lock_transport_http_error');
const transportOtherError = new Counter('lock_transport_other_error');
const successfulLockDuration = new Trend('successful_lock_duration', true);
const successfulLocks = new Counter('successful_locks');

export const options = {
  scenarios: {
    successful_lock: {
      executor: 'constant-vus',
      vus: concurrency,
      duration: `${warmupSeconds + steadySeconds}s`,
      gracefulStop: `${gracefulStopSeconds}s`,
    },
  },
  thresholds: {
    'lock_transport_failure{phase:steady}': ['rate<0.001'],
    'lock_transport_connection_refused{phase:steady}': ['count>=0'],
    'lock_transport_timeout{phase:steady}': ['count>=0'],
    'lock_transport_http_error{phase:steady}': ['count>=0'],
    'lock_transport_other_error{phase:steady}': ['count>=0'],
    'lock_business_success{phase:steady}': ['rate>0.999'],
    'lock_business_failure{phase:steady}': ['rate<0.001'],
    'lock_attempts{phase:steady}': ['count>0'],
    'lock_result_query_attempts{phase:steady}': ['count>=0'],
    'lock_ambiguous_outcomes{phase:steady}': ['count>=0'],
    'lock_query_recoveries{phase:steady}': ['count>=0'],
    'lock_retry_recoveries{phase:steady}': ['count>=0'],
    'lock_result_query_transport_failures{phase:steady}': ['count>=0'],
    'http_req_duration{phase:steady}': ['p(99)<30000'],
    'successful_lock_duration{phase:steady}': ['p(99)<30000'],
    'successful_locks{phase:steady}': ['count>0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  summaryTimeUnit: 'ms',
  discardResponseBodies: false,
};

export function setup() {
  return {
    steadyAtMs: Date.now() + warmupSeconds * 1000,
  };
}

function pad(value, width) {
  return String(value).padStart(width, '0').slice(-width);
}

function recordTransportFailure(response, tags) {
  if (response.status !== 0) {
    transportHttpError.add(1, tags);
    return;
  }

  const detail = String(response.error || '').toLowerCase();
  if (detail.includes('connection refused')) {
    transportConnectionRefused.add(1, tags);
  } else if (detail.includes('timeout') || detail.includes('deadline exceeded')) {
    transportTimeout.add(1, tags);
  } else {
    transportOtherError.add(1, tags);
  }
}

function responsePayload(response) {
  if (response.status !== 200) {
    return null;
  }
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function successfulPayload(payload) {
  return payload !== null && payload.code === '0000' && payload.data?.teamId;
}

function ambiguousLockOutcome(response, payload) {
  return response.status === 0
    || response.status >= 500
    || (response.status === 200 && payload === null)
    || (response.status === 200 && payload?.code === '0003')
    || (response.status === 200 && payload?.code === '0000' && !payload.data?.teamId);
}

function queryCommittedResult(queryBody, headers, tags) {
  queryAttempts.add(1, tags);
  const response = http.post(
    `${baseUrl}/api/v1/gbm/trade/query_market_pay_order`,
    queryBody,
    {
      headers,
      tags: { ...tags, operation: 'query_committed_lock_result' },
      timeout: `${requestTimeoutSeconds}s`,
    },
  );
  if (response.status === 0 || response.status >= 500) {
    queryTransportFailures.add(1, tags);
  }
  return successfulPayload(responsePayload(response));
}

export default function (data) {
  const phase = Date.now() < data.steadyAtMs ? 'warmup' : 'steady';
  const iteration = exec.scenario.iterationInTest + 1;
  const userId = `perf_${runId}_${iteration}`;
  const outTradeNo = pad(iteration, 12);
  const lockBody = JSON.stringify({
    userId,
    teamId: null,
    activityId,
    goodsId,
    orderPrice,
    source,
    channel,
    outTradeNo,
    notifyConfigVO: {
      notifyType: 'MQ',
      notifyMQ: null,
      notifyUrl: null,
    },
  });
  const tags = { phase };
  const headers = {
    'Content-Type': 'application/json',
    'X-Internal-Token': internalToken,
  };
  const queryBody = JSON.stringify({ userId, source, channel, outTradeNo });
  const startedAt = Date.now();
  let succeeded = false;
  let clearBusinessFailure = false;
  let finalResponse = null;

  queryAttempts.add(0, tags);
  ambiguousOutcomes.add(0, tags);
  queryRecoveries.add(0, tags);
  retryRecoveries.add(0, tags);
  queryTransportFailures.add(0, tags);

  for (let attempt = 1; attempt <= lockMaxAttempts; attempt += 1) {
    lockAttempts.add(1, tags);
    finalResponse = http.post(
      `${baseUrl}/api/v1/gbm/trade/lock_market_pay_order`,
      lockBody,
      {
        headers,
        tags: { phase, operation: 'successful_new_team_lock' },
        timeout: `${requestTimeoutSeconds}s`,
      },
    );
    const payload = responsePayload(finalResponse);
    if (successfulPayload(payload)) {
      succeeded = true;
      if (attempt > 1) {
        retryRecoveries.add(1, tags);
      }
      break;
    }

    if (!ambiguousLockOutcome(finalResponse, payload)) {
      clearBusinessFailure = true;
      break;
    }

    ambiguousOutcomes.add(1, tags);
    if (finalResponse.status !== 200) {
      recordTransportFailure(finalResponse, tags);
    }
    if (queryCommittedResult(queryBody, headers, tags)) {
      queryRecoveries.add(1, tags);
      succeeded = true;
      break;
    }
    if (attempt < lockMaxAttempts && retryBackoffMillis > 0) {
      sleep(retryBackoffMillis / 1000);
    }
  }

  transportFailure.add(!succeeded && !clearBusinessFailure, tags);
  businessSuccess.add(Boolean(succeeded), tags);
  businessFailure.add(clearBusinessFailure, tags);
  if (succeeded) {
    successfulLockDuration.add(Date.now() - startedAt, tags);
    successfulLocks.add(1, tags);
  }

  check(finalResponse, {
    'lock result confirmed': () => Boolean(succeeded),
  }, tags);
}

export function handleSummary(data) {
  const reportFile = __ENV.REPORT_FILE || 'group-load-k6-summary.json';
  return {
    [`/results/${reportFile}`]: JSON.stringify(data, null, 2),
    stdout: `k6 summary written to ${reportFile}\n`,
  };
}

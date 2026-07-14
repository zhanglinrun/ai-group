import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const concurrency = Number(__ENV.CONCURRENCY || 20);
const warmupSeconds = Number(__ENV.WARMUP_SECONDS || 120);
const steadySeconds = Number(__ENV.STEADY_SECONDS || 600);
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8091';
const internalToken = __ENV.INTERNAL_TOKEN || '';
const activityId = Number(__ENV.ACTIVITY_ID || 199901);
const goodsId = __ENV.GOODS_ID || '9890002';
const runId = __ENV.RUN_ID || 'local01';
const source = `p${runId}`.slice(0, 8);
const channel = 'load';

const businessSuccess = new Rate('lock_business_success');
const businessFailure = new Rate('lock_business_failure');
const transportFailure = new Rate('lock_transport_failure');
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
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'http_req_failed{phase:steady}': ['rate<0.001'],
    'lock_transport_failure{phase:steady}': ['rate<0.001'],
    'lock_transport_connection_refused{phase:steady}': ['count>=0'],
    'lock_transport_timeout{phase:steady}': ['count>=0'],
    'lock_transport_http_error{phase:steady}': ['count>=0'],
    'lock_transport_other_error{phase:steady}': ['count>=0'],
    'lock_business_success{phase:steady}': ['rate>0.999'],
    'lock_business_failure{phase:steady}': ['rate<0.001'],
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

export default function (data) {
  const phase = Date.now() < data.steadyAtMs ? 'warmup' : 'steady';
  const iteration = exec.scenario.iterationInTest + 1;
  const body = JSON.stringify({
    userId: `perf_${runId}_${iteration}`,
    teamId: null,
    activityId,
    goodsId,
    source,
    channel,
    outTradeNo: pad(iteration, 12),
    notifyConfigVO: {
      notifyType: 'MQ',
      notifyMQ: null,
      notifyUrl: null,
    },
  });

  const response = http.post(
    `${baseUrl}/api/v1/gbm/trade/lock_market_pay_order`,
    body,
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Internal-Token': internalToken,
      },
      tags: {
        phase,
        operation: 'successful_new_team_lock',
      },
      timeout: '30s',
    },
  );

  const transportOk = response.status === 200;
  let payload = null;
  if (transportOk) {
    try {
      payload = response.json();
    } catch (_) {
      payload = null;
    }
  }
  const succeeded = transportOk && payload !== null && payload.code === '0000' && payload.data?.teamId;
  const tags = { phase };
  transportFailure.add(!transportOk, tags);
  if (!transportOk) {
    recordTransportFailure(response, tags);
  }
  businessSuccess.add(Boolean(succeeded), tags);
  businessFailure.add(transportOk && !succeeded, tags);
  if (succeeded) {
    successfulLockDuration.add(response.timings.duration, tags);
    successfulLocks.add(1, tags);
  }

  check(response, {
    'HTTP 200': () => transportOk,
    'business code 0000 with teamId': () => Boolean(succeeded),
  }, tags);
}

export function handleSummary(data) {
  const reportFile = __ENV.REPORT_FILE || 'group-load-k6-summary.json';
  return {
    [`/results/${reportFile}`]: JSON.stringify(data, null, 2),
    stdout: `k6 summary written to ${reportFile}\n`,
  };
}

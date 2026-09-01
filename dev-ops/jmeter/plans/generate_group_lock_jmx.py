#!/usr/bin/env python3
"""Build a JMeter plan that measures only Group's lock-order HTTP endpoint.

The plan calls Group directly (no registration, login, Gateway, Pay service or
payment provider).  It still uses the same signed internal identity headers the
Gateway would forward, so the controller's authenticated-user binding remains
enabled during the benchmark.
"""

from __future__ import annotations

from pathlib import Path


L = chr(60)
R = chr(62)


def el(name: str, attrs: dict[str, str] | None = None, text: str | None = None) -> str:
    attrs_text = "".join(f' {key}="{value}"' for key, value in (attrs or {}).items())
    if text is None:
        return f"{L}{name}{attrs_text}{R}"
    return f"{L}{name}{attrs_text}{R}{text}{L}/{name}{R}"


def close(name: str) -> str:
    return f"{L}/{name}{R}"


def prop(kind: str, name: str, value: str) -> str:
    return el(f"{kind}Prop", {"name": name}, value)


def s(name: str, value: str) -> str:
    return prop("string", name, value)


def b(name: str, value: str) -> str:
    return prop("bool", name, value)


def i(name: str, value: str) -> str:
    return prop("int", name, value)


def tree(*children: str) -> str:
    return el("hashTree") + "".join(children) + close("hashTree")


def argument(name: str, value: str) -> str:
    return (
        el("elementProp", {"name": name, "elementType": "Argument"})
        + s("Argument.name", name)
        + s("Argument.value", value)
        + s("Argument.metadata", "=")
        + close("elementProp")
    )


def header(name: str, value: str) -> str:
    return (
        el("elementProp", {"name": "", "elementType": "Header"})
        + s("Header.name", name)
        + s("Header.value", value)
        + close("elementProp")
    )


def http_post(name: str, path: str, body: str) -> str:
    return (
        el(
            "HTTPSamplerProxy",
            {
                "guiclass": "HttpTestSampleGui",
                "testclass": "HTTPSamplerProxy",
                "testname": name,
            },
        )
        + s("HTTPSampler.path", path)
        + s("HTTPSampler.method", "POST")
        + b("HTTPSampler.postBodyRaw", "true")
        + b("HTTPSampler.follow_redirects", "false")
        + el("elementProp", {"name": "HTTPsampler.Arguments", "elementType": "Arguments"})
        + el("collectionProp", {"name": "Arguments.arguments"})
        + el("elementProp", {"name": "", "elementType": "HTTPArgument"})
        + b("HTTPArgument.always_encode", "false")
        + s("Argument.value", body)
        + s("Argument.metadata", "=")
        + close("elementProp")
        + close("collectionProp")
        + close("elementProp")
        + close("HTTPSamplerProxy")
    )


def assert_http_200() -> str:
    return (
        el(
            "ResponseAssertion",
            {
                "guiclass": "AssertionGui",
                "testclass": "ResponseAssertion",
                "testname": "HTTP 200",
            },
        )
        + el("collectionProp", {"name": "Asserion.test_strings"})
        + s("49586", "200")
        + close("collectionProp")
        + s("Assertion.test_field", "Assertion.response_code")
        + b("Assertion.assume_success", "false")
        + i("Assertion.test_type", "8")
        + close("ResponseAssertion")
    )


def assert_contains(name: str, needle: str) -> str:
    return (
        el(
            "ResponseAssertion",
            {
                "guiclass": "AssertionGui",
                "testclass": "ResponseAssertion",
                "testname": name,
            },
        )
        + el("collectionProp", {"name": "Asserion.test_strings"})
        + s("0", needle)
        + close("collectionProp")
        + s("Assertion.test_field", "Assertion.response_data")
        + b("Assertion.assume_success", "false")
        + i("Assertion.test_type", "2")
        + close("ResponseAssertion")
    )


def test_data_preprocessor() -> str:
    script = """import groovy.json.JsonOutput
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

def secret = System.getenv('AI_GROUP_IDENTITY_SIGNING_SECRET')
def token = System.getenv('AI_GROUP_INTERNAL_TOKEN')
if (secret == null || secret.isBlank() || token == null || token.isBlank()) {
    throw new IllegalStateException('AI_GROUP_IDENTITY_SIGNING_SECRET and AI_GROUP_INTERNAL_TOKEN are required')
}
def userSequence = props.get('groupLockUserSequence')
if (!(userSequence instanceof AtomicLong)) {
    synchronized (props) {
        userSequence = props.get('groupLockUserSequence')
        if (!(userSequence instanceof AtomicLong)) {
            userSequence = new AtomicLong(System.currentTimeMillis() * 1000L)
            props.put('groupLockUserSequence', userSequence)
        }
    }
}
long userId = userSequence.incrementAndGet()
long now = System.currentTimeMillis() / 1000L
def encoder = Base64.getUrlEncoder().withoutPadding()
def header = encoder.encodeToString('{\\"alg\\":\\"HS256\\",\\"typ\\":\\"JWT\\"}'.getBytes('UTF-8'))
def payload = encoder.encodeToString(JsonOutput.toJson([
        sub: String.valueOf(userId), iss: 'ai-group-gateway', aud: ['ai-group-internal'],
        iat: now, exp: now + 300L, jti: UUID.randomUUID().toString(), username: 'jmeter', role: 'USER'
]).getBytes('UTF-8'))
def mac = Mac.getInstance('HmacSHA256')
mac.init(new SecretKeySpec(MessageDigest.getInstance('SHA-256').digest(secret.getBytes('UTF-8')), 'HmacSHA256'))
def jwt = header + '.' + payload + '.' + encoder.encodeToString(mac.doFinal((header + '.' + payload).getBytes('UTF-8')))
vars.put('userId', String.valueOf(userId))
vars.put('outTradeNo', 'jmeter-lock-' + UUID.randomUUID().toString().replace('-', ''))
vars.put('internalToken', token)
vars.put('internalJwt', jwt)
"""
    return (
        el(
            "JSR223PreProcessor",
            {
                "guiclass": "TestBeanGUI",
                "testclass": "JSR223PreProcessor",
                "testname": "Generate isolated signed user and idempotency key",
            },
        )
        + s("cacheKey", "true")
        + s("filename", "")
        + s("parameters", "")
        + s("script", script)
        + s("scriptLanguage", "groovy")
        + close("JSR223PreProcessor")
    )


def thread_group() -> str:
    return (
        el(
            "ThreadGroup",
            {
                "guiclass": "ThreadGroupGui",
                "testclass": "ThreadGroup",
                "testname": "Group lock-only users",
            },
        )
        + s("ThreadGroup.on_sample_error", "continue")
        + el("elementProp", {"name": "ThreadGroup.main_controller", "elementType": "LoopController"})
        + b("LoopController.continue_forever", "true")
        + s("LoopController.loops", "-1")
        + close("elementProp")
        + s("ThreadGroup.num_threads", "${__P(threads,20)}")
        + s("ThreadGroup.ramp_time", "${__P(rampup,1)}")
        + b("ThreadGroup.scheduler", "true")
        + s("ThreadGroup.duration", "${__P(duration,60)}")
        + s("ThreadGroup.delay", "0")
        + close("ThreadGroup")
    )


def build() -> str:
    body = (
        '{"userId":"${userId}","teamId":null,'
        '"activityId":${__P(activityId,100201)},'
        '"goodsId":"${__P(goodsId,9890002)}",'
        '"orderPrice":${__P(orderPrice,12.00)},'
        '"source":"${__P(source,s01)}","channel":"${__P(channel,c01)}",'
        '"outTradeNo":"${outTradeNo}","notifyConfigVO":{"notifyType":"MQ"}}'
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        + el("jmeterTestPlan", {"version": "1.2", "properties": "5.0", "jmeter": "5.6.3"})
        + tree(
            el(
                "TestPlan",
                {
                    "guiclass": "TestPlanGui",
                    "testclass": "TestPlan",
                    "testname": "Group lock-order endpoint only",
                },
            )
            + s(
                "TestPlan.comments",
                "Calls only Group lock_market_pay_order. Authentication headers emulate Gateway-to-service propagation; "
                "registration, browser login, Gateway, Pay service and payment provider are excluded.",
            )
            + b("TestPlan.functional_mode", "false")
            + b("TestPlan.serialize_threadgroups", "true")
            + el("elementProp", {"name": "TestPlan.user_defined_variables", "elementType": "Arguments"})
            + el("collectionProp", {"name": "Arguments.arguments"})
            + argument("host", "${__P(host,127.0.0.1)}")
            + argument("port", "${__P(port,8091)}")
            + close("collectionProp")
            + close("elementProp")
            + close("TestPlan"),
            tree(
                el(
                    "ConfigTestElement",
                    {
                        "guiclass": "HttpDefaultsGui",
                        "testclass": "ConfigTestElement",
                        "testname": "Group HTTP defaults",
                    },
                )
                + s("HTTPSampler.protocol", "http")
                + s("HTTPSampler.domain", "${host}")
                + s("HTTPSampler.port", "${port}")
                + s("HTTPSampler.implementation", "HttpClient4")
                + b("HTTPSampler.use_keepalive", "true")
                + s("HTTPSampler.connect_timeout", "5000")
                + s("HTTPSampler.response_timeout", "30000")
                + close("ConfigTestElement"),
                tree(),
                el(
                    "HeaderManager",
                    {
                        "guiclass": "HeaderPanel",
                        "testclass": "HeaderManager",
                        "testname": "Authenticated JSON headers",
                    },
                )
                + el("collectionProp", {"name": "HeaderManager.headers"})
                + header("Content-Type", "application/json")
                + header("X-Gateway-Request", "true")
                + header("X-Internal-Token", "${internalToken}")
                + header("X-Internal-Jwt", "${internalJwt}")
                + close("collectionProp")
                + close("HeaderManager"),
                tree(),
                thread_group(),
                tree(
                    http_post("POST Group lock_market_pay_order", "/api/v1/gbm/trade/lock_market_pay_order", body),
                    tree(
                        test_data_preprocessor(),
                        tree(),
                        assert_http_200(),
                        tree(),
                        assert_contains("Lock business code is 0000", '\\"code\\":\\"0000\\"'),
                        tree(),
                    ),
                ),
            ),
        )
        + close("jmeterTestPlan")
    )


def main() -> None:
    output = Path(__file__).with_name("group-lock-only.jmx")
    output.write_text(build(), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()

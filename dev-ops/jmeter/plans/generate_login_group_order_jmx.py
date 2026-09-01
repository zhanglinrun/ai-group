#!/usr/bin/env python3
"""Build a JMeter plan for register -> login -> group order creation.

The order endpoint is intentionally create_pay_order, not a QR-code or payment
callback endpoint.  In group mode it synchronously executes the Group lock, but
with ALIPAY_ENABLED=false it does not call an external payment provider.
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
    script = """def sequence = (vars.getObject('loadSequence') ?: 0) + 1
vars.putObject('loadSequence', sequence)
def runId = props.get('runId', 'local')
def prefix = props.get('usernamePrefix', 'jmeter')
def suffix = runId + '-' + ctx.getThreadNum() + '-' + sequence
vars.put('username', prefix + '-' + suffix)
vars.put('password', props.get('password', 'JmeterPassw0rd'))
vars.put('requestId', 'jmeter-order-' + suffix)
"""
    return (
        el(
            "JSR223PreProcessor",
            {
                "guiclass": "TestBeanGUI",
                "testclass": "JSR223PreProcessor",
                "testname": "Generate isolated account and request IDs",
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
                "testname": "Browser login and group-order users",
            },
        )
        + s("ThreadGroup.on_sample_error", "continue")
        + el("elementProp", {"name": "ThreadGroup.main_controller", "elementType": "LoopController"})
        + b("LoopController.continue_forever", "false")
        + s("LoopController.loops", "${__P(loops,1)}")
        + close("elementProp")
        + s("ThreadGroup.num_threads", "${__P(threads,20)}")
        + s("ThreadGroup.ramp_time", "${__P(rampup,10)}")
        + b("ThreadGroup.scheduler", "false")
        + close("ThreadGroup")
    )


def transaction_controller() -> str:
    return (
        el(
            "TransactionController",
            {
                "guiclass": "TransactionControllerGui",
                "testclass": "TransactionController",
                "testname": "Register + login + group order",
            },
        )
        + b("TransactionController.includeTimers", "false")
        + b("TransactionController.parent", "true")
        + close("TransactionController")
    )


def build() -> str:
    register_body = '{"username":"${username}","password":"${password}"}'
    login_body = '{"username":"${username}","password":"${password}"}'
    order_body = (
        '{"requestId":"${requestId}",'
        '"productId":"${__P(productId,9890002)}",'
        '"productCode":"${__P(productCode,QUOTA_LIGHT)}",'
        '"activityId":${__P(activityId,100201)},"marketType":1}'
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
                    "testname": "Gateway login to group-order lock",
                },
            )
            + s(
                "TestPlan.comments",
                "Registers a unique local test account, logs in through Gateway, then creates a group order. "
                "The order creation path synchronously locks the Group order. Keep ALIPAY_ENABLED=false.",
            )
            + b("TestPlan.functional_mode", "false")
            + b("TestPlan.serialize_threadgroups", "true")
            + el("elementProp", {"name": "TestPlan.user_defined_variables", "elementType": "Arguments"})
            + el("collectionProp", {"name": "Arguments.arguments"})
            + argument("host", "${__P(host,127.0.0.1)}")
            + argument("port", "${__P(port,8080)}")
            + close("collectionProp")
            + close("elementProp")
            + close("TestPlan"),
            tree(
                el(
                    "ConfigTestElement",
                    {
                        "guiclass": "HttpDefaultsGui",
                        "testclass": "ConfigTestElement",
                        "testname": "Gateway HTTP defaults",
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
                    "CookieManager",
                    {
                        "guiclass": "CookiePanel",
                        "testclass": "CookieManager",
                        "testname": "Browser cookie jar",
                    },
                )
                + el("collectionProp", {"name": "CookieManager.cookies"})
                + close("collectionProp")
                + b("CookieManager.clearEachIteration", "true")
                + b("CookieManager.controlledByThread", "false")
                + close("CookieManager"),
                tree(),
                el(
                    "HeaderManager",
                    {
                        "guiclass": "HeaderPanel",
                        "testclass": "HeaderManager",
                        "testname": "JSON requests",
                    },
                )
                + el("collectionProp", {"name": "HeaderManager.headers"})
                + header("Content-Type", "application/json")
                + header("Accept", "application/json")
                + close("collectionProp")
                + close("HeaderManager"),
                tree(),
                thread_group(),
                tree(
                    transaction_controller(),
                    tree(
                        http_post("Register test account", "/api/auth/register", register_body),
                        tree(
                            test_data_preprocessor(),
                            tree(),
                            assert_http_200(),
                            tree(),
                            assert_contains("Register business code 200", '"code":200'),
                            tree(),
                        ),
                        http_post("Login", "/api/auth/login", login_body),
                        tree(
                            assert_http_200(),
                            tree(),
                            assert_contains("Login business code 200", '"code":200'),
                            tree(),
                            assert_contains("Login is authenticated", '"authenticated":true'),
                            tree(),
                        ),
                        http_post("Create group order (locks group order)", "/api/v1/alipay/create_pay_order", order_body),
                        tree(
                            assert_http_200(),
                            tree(),
                            assert_contains("Group order business code 0000", '"code":"0000"'),
                            tree(),
                        ),
                    ),
                ),
            ),
        )
        + close("jmeterTestPlan")
        + "\n"
    )


def main() -> None:
    output = Path(__file__).with_name("login-group-order.jmx")
    output.write_text(build(), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()

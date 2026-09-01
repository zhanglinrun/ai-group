#!/usr/bin/env python3
"""Build quota-ledger.jmx without embedding raw XML tags in this file."""

from pathlib import Path

L = chr(60)
R = chr(62)


def el(name: str, attrs: dict[str, str] | None = None, text: str | None = None) -> str:
    attr = "".join(f' {key}="{value}"' for key, value in (attrs or {}).items())
    if text is None:
        return f"{L}{name}{attr}{R}"
    return f"{L}{name}{attr}{R}{text}{L}/{name}{R}"


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


def longp(name: str, value: str) -> str:
    return prop("long", name, value)


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


def http_raw(name: str, path: str, body: str) -> str:
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


def json_extract(name: str, ref: str, expr: str) -> str:
    return (
        el(
            "JSONPostProcessor",
            {
                "guiclass": "JSONPostProcessorGui",
                "testclass": "JSONPostProcessor",
                "testname": name,
            },
        )
        + s("JSONPostProcessor.referenceNames", ref)
        + s("JSONPostProcessor.jsonPathExprs", expr)
        + s("JSONPostProcessor.match_numbers", "1")
        + s("JSONPostProcessor.defaultValues", "MISSING")
        + close("JSONPostProcessor")
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


def loop_controller(loops: str) -> str:
    return (
        el(
            "LoopController",
            {
                "guiclass": "LoopControlPanel",
                "testclass": "LoopController",
                "testname": "Loop",
            },
        )
        + b("LoopController.continue_forever", "false")
        + s("LoopController.loops", loops)
        + close("LoopController")
    )


def thread_group(name: str, threads: str, ramp: str, loops: str, scheduler: bool = False) -> str:
    body = (
        el(
            "ThreadGroup",
            {
                "guiclass": "ThreadGroupGui",
                "testclass": "ThreadGroup",
                "testname": name,
            },
        )
        + s("ThreadGroup.on_sample_error", "continue")
        + el("elementProp", {"name": "ThreadGroup.main_controller", "elementType": "LoopController"})
        + b("LoopController.continue_forever", "false" if not scheduler else "false")
        + s("LoopController.loops", loops)
        + close("elementProp")
        + s("ThreadGroup.num_threads", threads)
        + s("ThreadGroup.ramp_time", ramp)
        + b("ThreadGroup.scheduler", "true" if scheduler else "false")
        + s("ThreadGroup.duration", "${__P(duration,60)}")
        + s("ThreadGroup.delay", "0")
        + b("ThreadGroup.same_user_on_next_iteration", "true")
        + close("ThreadGroup")
    )
    return body


def setup_group() -> str:
    return (
        el(
            "SetupThreadGroup",
            {
                "guiclass": "SetupThreadGroupGui",
                "testclass": "SetupThreadGroup",
                "testname": "Seed quota users",
            },
        )
        + s("ThreadGroup.on_sample_error", "continue")
        + el("elementProp", {"name": "ThreadGroup.main_controller", "elementType": "LoopController"})
        + b("LoopController.continue_forever", "false")
        + s("LoopController.loops", "${__P(users,50)}")
        + close("elementProp")
        + s("ThreadGroup.num_threads", "1")
        + s("ThreadGroup.ramp_time", "0")
        + b("ThreadGroup.scheduler", "false")
        + close("SetupThreadGroup")
    )


def build() -> str:
    # __intSum is 32-bit. Keep userBase + offset below Integer.MAX_VALUE.
    seed_body = '{"userId":${__intSum(${__P(userBase,1800000000)},${__intSum(${__counter(TRUE)},-1)})}}'
    idem_body = (
        '{"userId":${__P(userBase,1800000000)},"amount":1,"minAmount":1,'
        '"abilityCode":"llm","ownerService":"legacy","requestId":"jmeter-idem-${__P(runId,local)}"}'
    )
    tp_body = (
        '{"userId":${__intSum(${__P(userBase,1800000000)},${__intSum(${__threadNum},-1)})},'
        '"amount":1,"minAmount":1,"abilityCode":"llm","ownerService":"legacy",'
        '"requestId":"jmeter-tp-${__time()}-${__threadNum}-${__counter(TRUE)}"}'
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
                    "testname": "Member quota ledger",
                },
            )
            + s("TestPlan.comments", "HTTP idempotency plus freeze throughput. Ledger invariants stay in Maven IT.")
            + b("TestPlan.functional_mode", "false")
            + b("TestPlan.serialize_threadgroups", "true")
            + el("elementProp", {"name": "TestPlan.user_defined_variables", "elementType": "Arguments"})
            + el("collectionProp", {"name": "Arguments.arguments"})
            + argument("host", "${__P(host,127.0.0.1)}")
            + argument("port", "${__P(port,18082)}")
            + close("collectionProp")
            + close("elementProp")
            + close("TestPlan"),
            tree(
                el(
                    "ConfigTestElement",
                    {
                        "guiclass": "HttpDefaultsGui",
                        "testclass": "ConfigTestElement",
                        "testname": "HTTP Defaults",
                    },
                )
                + s("HTTPSampler.protocol", "http")
                + s("HTTPSampler.domain", "${host}")
                + s("HTTPSampler.port", "${port}")
                + s("HTTPSampler.implementation", "HttpClient4")
                + b("HTTPSampler.use_keepalive", "true")
                + s("HTTPSampler.connect_timeout", "5000")
                + s("HTTPSampler.response_timeout", "15000")
                + close("ConfigTestElement"),
                tree(),
                el(
                    "HeaderManager",
                    {
                        "guiclass": "HeaderPanel",
                        "testclass": "HeaderManager",
                        "testname": "JSON and internal token",
                    },
                )
                + el("collectionProp", {"name": "HeaderManager.headers"})
                + header("Content-Type", "application/json")
                + header("X-Internal-Token", "${__P(token,)}")
                + close("collectionProp")
                + close("HeaderManager"),
                tree(),
                setup_group(),
                tree(
                    http_raw("Seed init-free", "/internal/members/init-free", seed_body),
                    tree(
                        assert_http_200(),
                        tree(),
                        assert_contains("Business code 200", '"code":200'),
                        tree(),
                    ),
                ),
                thread_group("Idempotency same requestId", "${__P(idemThreads,20)}", "1", "${__P(idemLoops,10)}"),
                tree(
                    http_raw("Freeze same requestId", "/internal/member/quota/reservations", idem_body),
                    tree(
                        assert_http_200(),
                        tree(),
                        assert_contains("Business code 200", '"code":200'),
                        tree(),
                    ),
                ),
                thread_group(
                    "Freeze throughput unique requestId",
                    "${__P(threads,50)}",
                    "${__P(rampup,10)}",
                    "-1",
                    scheduler=True,
                ),
                tree(
                    http_raw("Freeze unique requestId", "/internal/member/quota/reservations", tp_body),
                    tree(
                        assert_http_200(),
                        tree(),
                        assert_contains("Business code 200", '"code":200'),
                        tree(),
                        json_extract("Extract freezeId", "freezeId", "$.data.freezeId"),
                        tree(),
                    ),
                    http_raw(
                        "Release after freeze",
                        "/internal/member/quota/reservations/${freezeId}/release",
                        "{}",
                    ),
                    tree(assert_http_200(), tree()),
                ),
            ),
        )
        + close("jmeterTestPlan")
        + "\n"
    )


def main() -> None:
    out = Path(__file__).with_name("quota-ledger.jmx")
    xml = build()
    if "testdependencies" in xml:
        raise SystemExit("generated JMX was polluted; refuse to write")
    out.write_text(xml, encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()

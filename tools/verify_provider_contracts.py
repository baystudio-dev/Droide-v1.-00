#!/usr/bin/env python3
"""Compile and test production provider serializers on the JVM without live API calls."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", action="append", required=True)
    args = parser.parse_args()
    jars = sorted({p.resolve() for directory in args.jars for p in Path(directory).glob("*.jar")})
    for prefix in ("kotlin-compiler", "kotlin-stdlib", "serialization-core", "serialization-json", "junit", "hamcrest", "coroutines-core", "okhttp", "okio"):
        if not any(prefix in p.name for p in jars):
            raise SystemExit(f"Missing externally provisioned JVM dependency: {prefix}")
    classpath = os.pathsep.join(map(str, jars))
    production = ["ProviderToolSchema", "ProviderFailure", "ProviderModelCatalog", "AgentToolCatalog",
                  "ModelsDevCatalog", "ProviderModelRequestPolicy", "ProviderRuntimeCapabilities", "OpenAiResponsesAdapter", "McpToolIdentity",
                  "GoogleGenerateContentAdapter", "AnthropicMessagesAdapter", "ProviderRegistry", "ProviderRuntimeGuard",
                  "ProviderModelIdentity", "ProviderModelRequestIdentity", "ProviderModelCertificationRegistry",
                  "ModelCapabilityRegistry", "ReasoningEffort", "ContextBudget", "LlmStream", "AgentPluginJsonSchema", "AgentIdeRequest", "GradleTaskPath",
                  "LlmClient", "ProviderTransportV2", "ProviderRequestAuthorizer", "ProviderCredentialChain", "ProviderDefaultCredentialSources",
                  "AwsSharedProfileParser", "AwsSigV4Signer", "NetworkSecurity", "OkHttpAwait", "AgentTimeoutPolicy", "ProviderCredentialResponsePolicy", "ProviderRetryPolicy", "ProviderStreamFailure"]
    tests = ["ModelsDevProjectionTest", "ProviderToolSchemaTest", "ProviderSchemaCompatibilityTest", "ProviderModelRoutingTest", "ProviderRuntimeCapabilityTest",
             "OpenAiResponsesAdapterTest", "ProviderTransportSchemaTest", "ProviderBufferedCompletionTest", "ProviderStreamCompletionTest", "McpToolIdentityTest", "ProviderRequestContractsTest", "GoogleToolContinuationTest", "GoogleNativeRequestValidationTest", "ModelRequestPolicyTest",
             "ProviderFailureTest", "ProviderModelCatalogTest", "ProviderModelIdentityTest", "AnthropicThinkingTest", "ProviderLongRunningTransportTest"]
    sources = [ROOT / "app/src/main/java/com/baystudio/droide/core" / f"{name}.kt" for name in production]
    sources += [ROOT / "app/src/test/java/com/baystudio/droide/core" / f"{name}.kt" for name in tests]
    sources.append(ROOT / "app/src/test/java/com/baystudio/droide/core/ProviderLoopbackServer.kt")
    sources.append(ROOT / "tools/host-regressions/AndroidContextBoundary.kt")
    with tempfile.TemporaryDirectory(prefix="droide-provider-contracts-") as temp:
        classes = Path(temp) / "classes"
        subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-jvm-target", "17", "-classpath", classpath, "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath, "org.junit.runner.JUnitCore",
                        *[f"com.baystudio.droide.core.{name}" for name in tests]], check=True)
    print("PROVIDER_CONTRACTS_HOST_VERIFICATION_OK")
    print("Production serializers and LlmClient buffered/SSE loopback transport tested; no live provider or device claim.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(f"Provider contract verification failed (exit {failure.returncode})") from None

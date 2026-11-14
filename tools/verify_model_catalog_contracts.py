#!/usr/bin/env python3
"""Offline models.dev snapshot parsing and production request-policy matrix.

Supply an immutable official repository archive and JVM jars explicitly. No API
credentials, network inference, Android runtime, or provider availability claims.
The only Android host stub supplies Context.filesDir; catalog refresh is not run.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import posixpath
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def merge_deep(base, override):
    result = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge_deep(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def apply_omit(target, paths):
    for path in paths:
        parts = path.split(".")
        parents = []
        current = target
        for part in parts[:-1]:
            value = current.get(part)
            if not isinstance(value, dict):
                break
            parents.append((current, part))
            current = value
        else:
            if parts[-1] not in current:
                continue
            del current[parts[-1]]
            for parent, key in reversed(parents):
                if parent[key]:
                    break
                del parent[key]


def flatten_archive(archive):
    """Mirror packages/core/src/generate.ts base-model inheritance, not guessed defaults."""
    dangling = []
    symlink_count = 0
    inheritance_count = 0
    with zipfile.ZipFile(archive) as z:
        names = set(z.namelist())
        top = next(name.split('/')[0] for name in names if name.endswith('/packages/core/src/generate.ts'))

        def read(name, seen=()):
            nonlocal symlink_count
            if name in seen:
                raise ValueError(f"Cyclic archive symlink: {name}")
            info = z.getinfo(name)
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                target = posixpath.normpath(posixpath.join(posixpath.dirname(name), z.read(name).decode()))
                if not target.startswith(top + '/'):
                    raise ValueError(f"Archive symlink leaves repository: {name}")
                return read(target, seen + (name,))
            return z.read(name)

        models = {}
        prefix = top + '/models/'
        for name in sorted(names):
            if name.startswith(prefix) and name.endswith('.toml'):
                model_id = name[len(prefix):-5]
                models[model_id] = tomllib.loads(read(name).decode()) | {'id': model_id}

        providers = {}
        prefix = top + '/providers/'
        for name in sorted(names):
            if name.startswith(prefix) and name.endswith('/provider.toml'):
                relative = name[len(prefix):]
                if relative.count('/') != 1:
                    continue
                provider_id = relative.split('/')[0]
                providers[provider_id] = tomllib.loads(read(name).decode()) | {'id': provider_id, 'models': {}}

        for name in sorted(names):
            if not (name.startswith(prefix) and name.endswith('.toml')):
                continue
            relative = name[len(prefix):]
            provider_id, sep, remainder = relative.partition('/models/')
            if not sep or provider_id not in providers:
                continue
            if (z.getinfo(name).external_attr >> 16) & 0o170000 == 0o120000:
                symlink_count += 1
            try:
                model = tomllib.loads(read(name).decode())
            except KeyError:
                dangling.append(relative)
                continue
            model_id = remainder[:-5]
            model['id'] = model_id
            base_id = model.pop('base_model', None)
            omit = model.pop('base_model_omit', [])
            if base_id is not None:
                if base_id not in models:
                    raise ValueError(f"Unresolved base_model {base_id}: {name}")
                base = {k: v for k, v in models[base_id].items()
                        if k not in {'id', 'benchmarks', 'license', 'links', 'weights'}}
                model = merge_deep(base, model)
                apply_omit(model, omit)
                model['canonical_model_id'] = base_id
                inheritance_count += 1
            # Match Zod's two documented defaults/preprocessors in generated metadata.
            for option in model.get('reasoning_options', []):
                if option.get('type') == 'effort':
                    option['values'] = [None if value == 'null' else value for value in option['values']]
            cost = model.get('cost')
            if isinstance(cost, dict):
                tiers = cost.get('tiers', [])
                for tier in tiers:
                    tier['tier'].setdefault('type', 'context')
                if len(tiers) == 1 and tiers[0]['tier']['type'] == 'context' and tiers[0]['tier']['size'] >= 200_000:
                    cost['context_over_200k'] = {k: v for k, v in tiers[0].items() if k != 'tier'}
            providers[provider_id]['models'][model_id] = model
        if any(not provider['models'] for provider in providers.values()):
            raise ValueError('Catalog snapshot contains a provider with no resolvable models')
        generator = z.read(top + '/packages/core/src/generate.ts')
    return providers, {
        'archive_sha256': hashlib.sha256(Path(archive).read_bytes()).hexdigest(),
        'generator_sha256': hashlib.sha256(generator).hexdigest(),
        'providers': len(providers),
        'provider_model_offerings': sum(len(p['models']) for p in providers.values()),
        'canonical_metadata_models': len(models),
        'decision_type_offerings_included': sum(m.get('type') == 'decision'
                                                for p in providers.values() for m in p['models'].values()),
        'inherited_model_offerings': inheritance_count,
        'model_symlinks': symlink_count,
        'dangling_model_symlinks_excluded': dangling,
        'inheritance_scope': 'generate.ts mergeDeep: nested objects merged; arrays/scalars replaced; id/benchmarks/license/links/weights not inherited; base_model_omit prunes empty parents',
    }


PROBE = r'''
package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val json = Json
    val root = json.parseToJsonElement(File(args[0]).readText()).jsonObject
    val snapshot = ModelsDevCatalog.parse(root.toString())
    ProviderRegistry.installDiscovered(snapshot.providers)
    ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
    val failures = mutableListOf<JsonObject>()
    val rows = mutableListOf<JsonObject>()
    val protocolCounts = linkedMapOf<String, Int>()
    val excludedProviders = linkedMapOf<String, Int>()
    val filteredRows = mutableListOf<JsonObject>()
    var conversational = 0
    var nonConversational = 0
    var encodings = 0
    var aliasEncodings = 0
    var noTool = 0
    var supportedEffortCount = 0
    val fixtures = Json.parseToJsonElement("""[
      {"type":"function","function":{"name":"mcp_schema_fixture","parameters":{"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"}},"required":["content"]}}}}}},
      {"type":"function","function":{"name":"plugin_schema_fixture","parameters":{"type":"object","properties":{"nested":{"type":"array","items":{"type":"array"}}}}}}
    ]""").jsonArray
    val tools = JsonArray(AgentToolCatalog.definitions + fixtures)
    snapshot.capabilities.forEach { cap ->
        val raw = root[cap.providerId]?.jsonObject?.get("models")?.jsonObject?.get(cap.modelId)?.jsonObject ?: error("Fixture identity missing")
        val provider = ProviderRegistry.findByCatalogId(cap.providerId)
        if (provider == null) {
            excludedProviders[cap.providerId] = (excludedProviders[cap.providerId] ?: 0) + 1
            return@forEach
        }
        val protocols = ProviderModelRequestPolicy.protocol(provider, cap.modelId)
        if (!ProviderModelRequestPolicy.isConversational(provider, cap.modelId)) {
            nonConversational++
            check(runCatching { ProviderModelRequestPolicy.requireConversational(provider, cap.modelId) }.isFailure)
            filteredRows += buildJsonObject {
                put("provider",provider.id); put("model",cap.modelId)
                put("transport_incompatible",cap.transportCompatible == false)
                put("output_modalities",JsonArray(cap.outputModalities.map(::JsonPrimitive)))
                put("supported_endpoints",JsonArray(cap.supportedEndpoints.map(::JsonPrimitive)))
            }
            return@forEach
        }
        conversational++
        protocolCounts[protocols.name] = (protocolCounts[protocols.name] ?: 0) + 1
        val toolSupport = ModelCapabilityRegistry.allowsToolUse(provider.id, cap.modelId)
        if (!toolSupport) {
            noTool++
            check(runCatching { ProviderModelRequestPolicy.requireToolSupport(provider, cap.modelId, true) }.isFailure) {
                "Explicitly text-only model accepted function tools"
            }
        }
        val supported = ReasoningSupport.supported(provider.id, cap.modelId)
        supportedEffortCount += supported.size
        if (cap.reasoningOptionsKnown && "effort" !in cap.reasoningOptionTypes &&
            protocols in setOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES) &&
            provider.id != "openrouter" && supported.isNotEmpty()) {
            failures += buildJsonObject {
                put("provider",provider.id); put("model",cap.modelId); put("check","named_effort_metadata_authority")
                put("reason","Budget/toggle-only metadata cannot prove a generic named-effort serializer")
            }
        }
        val modelOverride = raw["provider"] as? JsonObject
        if (modelOverride?.get("shape") == JsonPrimitive("responses") && protocols != ProviderWireProtocol.OPENAI_RESPONSES) {
            failures += buildJsonObject { put("provider",provider.id); put("model",cap.modelId); put("check","advertised_responses_route"); put("reason","provider.shape responses selected incompatible transport") }
        }
        val efforts = listOf<ReasoningEffort?>(null) + supported
        efforts.forEach { effort ->
            val request = buildJsonObject {
                put("model",cap.modelId)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role","system"); put("content","Use project tools when needed.") })
                    add(buildJsonObject { put("role","user"); put("content","ppp") })
                })
                if (toolSupport) { put("tools",tools); put("tool_choice","auto") }
                ReasoningSupport.apply(this, provider.id, cap.modelId, effort)
            }
            for (streaming in listOf(false,true)) {
                try {
                    ProviderModelRequestPolicy.requireConversational(provider, cap.modelId)
                    ProviderModelRequestPolicy.requireToolSupport(provider, cap.modelId, toolSupport)
                    val encoded = when (protocols) {
                        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> ProviderToolSchema.normalizeRequest(request).toString()
                        ProviderWireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter.encodeRequest(request.toString(),streaming,json)
                        ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.encodeRequest(request.toString(),streaming,json,provider.id)
                        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.encodeRequest(request.toString(),json,provider.id)
                    }
                    val wire = json.parseToJsonElement(encoded).jsonObject
                    cap.outputTokens?.let { limit ->
                        val declared = (wire["max_tokens"] as? JsonPrimitive)?.intOrNull
                            ?: (wire["max_output_tokens"] as? JsonPrimitive)?.intOrNull
                            ?: ((wire["generationConfig"] as? JsonObject)?.get("maxOutputTokens") as? JsonPrimitive)?.intOrNull
                        check(declared == null || declared <= limit) { "Derived output limit exceeds model cap" }
                    }
                    if (!toolSupport) check(wire["tools"] == null) { "Text-only model received tools" }
                    encodings++
                    if (cap.providerId == "google") {
                        val alias = provider.copy(id="gemini")
                        val aliasRequest = buildJsonObject {
                            request.forEach { (key,value) -> if (key != "reasoning_effort") put(key,value) }
                            ReasoningSupport.apply(this, alias.id, cap.modelId, effort)
                        }
                        GoogleGenerateContentAdapter.encodeRequest(aliasRequest.toString(),json,alias.id)
                        aliasEncodings++
                    }
                } catch (failure: Exception) {
                    failures += buildJsonObject {
                        put("provider",provider.id); put("model",cap.modelId); put("protocol",protocols.name)
                        put("effort",effort?.wireValue ?: "default"); put("streaming",streaming)
                        put("check","encode_request"); put("reason",failure.message.orEmpty())
                    }
                }
            }
        }
        rows += buildJsonObject {
            put("provider",provider.id); put("model",cap.modelId); put("protocol",protocols.name)
            put("tool_support",toolSupport); put("supported_efforts",JsonArray(supported.map { JsonPrimitive(it.wireValue) }))
        }
    }
    val report = buildJsonObject {
        put("scope","Actual production ModelsDevCatalog parser, provider registry, capability/reasoning policy, and four serializers; offline buffered/streaming request encoding. Google GenerateContent uses the same body in both modes. No live inference, remote acceptance, or Android runtime claim.")
        put("parsed_providers",root.size); put("parsed_capabilities",snapshot.capabilities.size)
        put("discovered_providers",snapshot.providers.size); put("registered_callable_providers",ProviderRegistry.all.size)
        put("conversational_offerings",conversational); put("non_conversational_offerings_filtered",nonConversational)
        put("text_only_offerings",noTool); put("supported_effort_profiles",supportedEffortCount)
        put("function_schema_fixtures_per_tool_capable_request",tools.size)
        put("request_encodings_passed",encodings); put("gemini_alias_encodings_passed",aliasEncodings)
        put("protocol_counts", buildJsonObject { protocolCounts.forEach { (key,value) -> put(key,value) } })
        put("unbound_catalog_providers", buildJsonObject { excludedProviders.forEach { (key,value) -> put(key,value) } })
        put("failures",JsonArray(failures)); put("rows",JsonArray(rows)); put("filtered_rows",JsonArray(filteredRows))
    }
    File(args[1]).writeText(report.toString())
    println("CATALOG_REQUEST_MATRIX offerings=$conversational encodings=$encodings alias_encodings=$aliasEncodings failures=${failures.size}")
    check(failures.isEmpty()) { "Catalog request matrix contains ${failures.size} failures; inspect report" }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--jars', action='append', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    catalog, flattening = flatten_archive(args.archive)
    fixture = args.output / 'catalog-flattened.json'
    fixture.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':')))
    (args.output / 'catalog-flattening.json').write_text(json.dumps(flattening, indent=2))
    jars = sorted({p.resolve() for d in args.jars for p in Path(d).glob('*.jar')})
    for prefix in ('kotlin-compiler', 'kotlin-stdlib', 'serialization-core', 'serialization-json', 'coroutines-core', 'okhttp', 'okio'):
        if not any(prefix in p.name for p in jars):
            raise SystemExit(f'Missing externally provisioned JVM dependency: {prefix}')
    classpath = os.pathsep.join(map(str, jars))
    production = ['ModelsDevCatalog', 'ProviderToolSchema', 'ProviderFailure', 'ProviderModelCatalog', 'AgentToolCatalog',
                  'ProviderModelRequestPolicy', 'ProviderRuntimeCapabilities', 'OpenAiResponsesAdapter',
                  'GoogleGenerateContentAdapter', 'AnthropicMessagesAdapter', 'ProviderRegistry', 'ProviderRuntimeGuard',
                  'ProviderModelIdentity', 'ProviderModelRequestIdentity', 'ProviderModelCertificationRegistry',
                  'ModelCapabilityRegistry', 'ReasoningEffort', 'ContextBudget', 'LlmStream', 'AgentPluginJsonSchema', 'AgentIdeRequest', 'GradleTaskPath',
                  'LlmClient', 'ProviderTransportV2', 'ProviderRequestAuthorizer', 'ProviderCredentialChain', 'ProviderDefaultCredentialSources',
                  'AwsSharedProfileParser', 'AwsSigV4Signer', 'NetworkSecurity', 'OkHttpAwait']
    sources = [ROOT / 'app/src/main/java/com/baystudio/droide/core' / f'{name}.kt' for name in production]
    (args.output / 'catalog-production-sources.json').write_text(json.dumps({
        'source_hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'probe_sha256': hashlib.sha256(PROBE.encode()).hexdigest(),
        'tool_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'jars': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},
        'android_host_stub': 'Context.filesDir only; initialize/refresh/cache/network methods are not invoked',
    }, indent=2))
    with tempfile.TemporaryDirectory(prefix='droide-catalog-contracts-') as temp:
        temp = Path(temp)
        context = temp / 'Context.kt'
        context.write_text('package android.content\nabstract class Context { abstract val filesDir: java.io.File }\n')
        probe = temp / 'CatalogRequestMatrix.kt'
        probe.write_text(PROBE)
        classes = temp / 'classes'
        compile_args = ['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-classpath', classpath, '-d', str(classes), *map(str, sources), str(context), str(probe)]
        with (args.output / 'catalog-compile.log').open('w') as log:
            subprocess.run(compile_args, stdout=log, stderr=subprocess.STDOUT, check=True)
        run_args = ['java', '-cp', str(classes) + os.pathsep + classpath, 'com.baystudio.droide.core.CatalogRequestMatrixKt',
                    str(fixture), str(args.output / 'catalog-request-matrix.json')]
        with (args.output / 'catalog-tests.log').open('w') as log:
            result = subprocess.run(run_args, stdout=log, stderr=subprocess.STDOUT)
        print((args.output / 'catalog-tests.log').read_text())
        if result.returncode:
            raise SystemExit(result.returncode)
    print('PUBLIC_MODEL_CATALOG_CONTRACTS_OK')


if __name__ == '__main__':
    main()

package com.baystudio.droide.core

/** Three original acquisition/receipt units, exactly one language server. */
internal object LocalUbuntuCSharpPolicy {
    const val FAMILY = "lsp.csharp-ls"
    const val VERSION = "0.28.0"
    const val COMMAND = "csharp-ls"
    const val SOURCE_COMMIT = "72671fc7fa810c158864e72485db68e8b9b25c3d"
    const val ENTRY = "tools/net10.0/any/CSharpLanguageServer.dll"
    const val BUILDHOST = "tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.dll"
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val EXECUTABLE = "$ROOT/payload/tree/$ENTRY"
    const val DISCOVERY_COMMAND = "$ROOT/payload/bin/$COMMAND"
    const val KEY = "$FAMILY@$VERSION"
    const val KIND = "local-ubuntu-csharp-v1"
    const val POLICY = "csharp-original-three-artifact-v1"
    const val SIGNATURE_POLICY = "Publisher artifact digest verified; embedded NuGet signature retained but not cryptographically verified."
    const val SHA512 = "a138b9c8f9de9dd6343e9b0d0c8f4d51637bba7e38606b7c2b08b1d91e8eefa39f12123057f6625f425812b4361e52841bd2856704ec127ff50144ce05170a99"
    const val SHA512_BASE64 = "oTi5yPnendY0PpsNDI9NUWN7un44YGt8Kwix2R6O76OfEhIwV/ZiX0JYErQ2HlKEG9KFZwTsEn/1AUTOBRcKmQ=="
    const val ASSET = "csharp-ls.0.28.0.nupkg"
    const val ASSET_URL = "https://api.nuget.org/v3-flatcontainer/csharp-ls/0.28.0/$ASSET"
    const val PROVENANCE = "https://api.nuget.org/v3/catalog0/data/2026.09.13.08.22.33/csharp-ls.0.28.0.json"
    const val ARTIFACT_BYTES = 21_643_814L
    const val MAX_FILES = 221
    const val MAX_TREE_BYTES = 59_497_796L
    val families = setOf(FAMILY, LocalUbuntuCSharpSdkPolicy.FAMILY, LocalUbuntuCSharpHelperPolicy.FAMILY)
    val dependencyKeys = listOf(LocalUbuntuCSharpHelperPolicy.KEY, LocalUbuntuCSharpSdkPolicy.KEY).sorted()
    fun isFamily(family: String) = family in families
    fun version(family: String) = recipe(family).version
    fun root(family: String) = "/opt/droide/packages/$family/${version(family)}/arm64-v8a"
    fun recipe(family: String): CSharpInstallRecipe = when (family) {
        FAMILY -> CSharpInstallRecipe(FAMILY, VERSION, COMMAND, CSharpArtifactRole.MANAGED_NUPKG,
            ASSET_URL, ASSET, CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA512, SHA512, SHA512_BASE64),
            ARTIFACT_BYTES, ARTIFACT_BYTES, MAX_FILES, MAX_TREE_BYTES, ENTRY, PROVENANCE)
        LocalUbuntuCSharpSdkPolicy.FAMILY -> LocalUbuntuCSharpSdkPolicy.recipe
        LocalUbuntuCSharpHelperPolicy.FAMILY -> LocalUbuntuCSharpHelperPolicy.recipe
        else -> error("No reviewed C# artifact for $family")
    }
    fun requested(family: String, version: String?): CSharpInstallRecipe = recipe(family).also {
        require(version == it.version) { "Choose exact $family ${it.version}; latest/custom and substitute runtimes are unavailable" }; it.validate()
    }

    // Original catalog names and uncompressed sizes, not invented per-member hashes.
    val catalogMembers: Map<String, Long> = """
csharp-ls.nuspec	831
tools/net10.0/any/DotnetToolSettings.xml	200
tools/net10.0/any/Argu.dll	382976
tools/net10.0/any/Castle.Core.dll	388608
tools/net10.0/any/CSharpLanguageServer.deps.json	55029
tools/net10.0/any/CSharpLanguageServer.dll	1220096
tools/net10.0/any/CSharpLanguageServer.runtimeconfig.json	330
tools/net10.0/any/FSharp.Control.AsyncSeq.dll	329216
tools/net10.0/any/FSharp.Core.dll	2393352
tools/net10.0/any/Humanizer.dll	355944
tools/net10.0/any/ICSharpCode.Decompiler.dll	2558464
tools/net10.0/any/Ionide.LanguageServerProtocol.dll	962048
tools/net10.0/any/Microsoft.Bcl.AsyncInterfaces.dll	20752
tools/net10.0/any/Microsoft.Build.Locator.dll	50720
tools/net10.0/any/Microsoft.CodeAnalysis.CSharp.dll	7079208
tools/net10.0/any/Microsoft.CodeAnalysis.CSharp.Features.dll	2365224
tools/net10.0/any/Microsoft.CodeAnalysis.CSharp.Workspaces.dll	966440
tools/net10.0/any/Microsoft.CodeAnalysis.dll	3103056
tools/net10.0/any/Microsoft.CodeAnalysis.Elfie.dll	145840
tools/net10.0/any/Microsoft.CodeAnalysis.ExternalAccess.RazorCompiler.dll	20816
tools/net10.0/any/Microsoft.CodeAnalysis.Features.dll	5693736
tools/net10.0/any/Microsoft.CodeAnalysis.Scripting.dll	137512
tools/net10.0/any/Microsoft.CodeAnalysis.VisualBasic.dll	4858664
tools/net10.0/any/Microsoft.CodeAnalysis.VisualBasic.Workspaces.dll	810792
tools/net10.0/any/Microsoft.CodeAnalysis.Workspaces.dll	4323112
tools/net10.0/any/Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.dll	52520
tools/net10.0/any/Microsoft.CodeAnalysis.Workspaces.MSBuild.dll	134992
tools/net10.0/any/Microsoft.DiaSymReader.dll	87168
tools/net10.0/any/Microsoft.Extensions.Caching.Abstractions.dll	37712
tools/net10.0/any/Microsoft.Extensions.Caching.Memory.dll	47952
tools/net10.0/any/Microsoft.Extensions.Configuration.Abstractions.dll	28496
tools/net10.0/any/Microsoft.Extensions.Configuration.Binder.dll	43344
tools/net10.0/any/Microsoft.Extensions.Configuration.dll	44880
tools/net10.0/any/Microsoft.Extensions.DependencyInjection.Abstractions.dll	65872
tools/net10.0/any/Microsoft.Extensions.DependencyInjection.dll	95568
tools/net10.0/any/Microsoft.Extensions.Logging.Abstractions.dll	66896
tools/net10.0/any/Microsoft.Extensions.Logging.Configuration.dll	28496
tools/net10.0/any/Microsoft.Extensions.Logging.Console.dll	76112
tools/net10.0/any/Microsoft.Extensions.Logging.dll	51536
tools/net10.0/any/Microsoft.Extensions.Options.ConfigurationExtensions.dll	21840
tools/net10.0/any/Microsoft.Extensions.Options.dll	65360
tools/net10.0/any/Microsoft.Extensions.Primitives.dll	44880
tools/net10.0/any/Microsoft.NET.StringTools.dll	33064
tools/net10.0/any/Microsoft.VisualStudio.SolutionPersistence.dll	172072
tools/net10.0/any/System.Composition.AttributedModel.dll	20280
tools/net10.0/any/System.Composition.Convention.dll	54536
tools/net10.0/any/System.Composition.Hosting.dll	56072
tools/net10.0/any/System.Composition.Runtime.dll	27400
tools/net10.0/any/System.Composition.TypedParts.dll	65848
tools/net10.0/any/System.Configuration.ConfigurationManager.dll	444728
tools/net10.0/any/System.Diagnostics.EventLog.dll	54032
tools/net10.0/any/System.Security.Cryptography.ProtectedData.dll	40248
tools/net10.0/any/BuildHost-net472/Microsoft.Bcl.AsyncInterfaces.dll	28432
tools/net10.0/any/BuildHost-net472/Microsoft.Build.Locator.dll	34848
tools/net10.0/any/BuildHost-net472/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.exe	115624
tools/net10.0/any/BuildHost-net472/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.exe.config	3555
tools/net10.0/any/BuildHost-net472/Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.dll	61776
tools/net10.0/any/BuildHost-net472/Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.pdb	17188
tools/net10.0/any/BuildHost-net472/Microsoft.IO.Redist.dll	131880
tools/net10.0/any/BuildHost-net472/System.Buffers.dll	23816
tools/net10.0/any/BuildHost-net472/System.IO.Pipelines.dll	85816
tools/net10.0/any/BuildHost-net472/System.Memory.dll	145200
tools/net10.0/any/BuildHost-net472/System.Numerics.Vectors.dll	110344
tools/net10.0/any/BuildHost-net472/System.Runtime.CompilerServices.Unsafe.dll	19256
tools/net10.0/any/BuildHost-net472/System.Text.Encodings.Web.dll	87816
tools/net10.0/any/BuildHost-net472/System.Text.Json.dll	777480
tools/net10.0/any/BuildHost-net472/System.Threading.Tasks.Extensions.dll	27960
tools/net10.0/any/BuildHost-netcore/Microsoft.Build.Locator.dll	50720
tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.deps.json	3478
tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.dll	112464
tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost.runtimeconfig.json	369
tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.dll	52520
tools/net10.0/any/BuildHost-netcore/Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.pdb	22804
tools/net10.0/any/cs/FSharp.Core.resources.dll	34568
tools/net10.0/any/cs/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	51024
tools/net10.0/any/cs/Microsoft.CodeAnalysis.CSharp.resources.dll	453968
tools/net10.0/any/cs/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/cs/Microsoft.CodeAnalysis.Features.resources.dll	196392
tools/net10.0/any/cs/Microsoft.CodeAnalysis.resources.dll	41296
tools/net10.0/any/cs/Microsoft.CodeAnalysis.Scripting.resources.dll	17704
tools/net10.0/any/cs/Microsoft.CodeAnalysis.VisualBasic.resources.dll	302416
tools/net10.0/any/cs/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24872
tools/net10.0/any/cs/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17232
tools/net10.0/any/cs/Microsoft.CodeAnalysis.Workspaces.resources.dll	40784
tools/net10.0/any/de/FSharp.Core.resources.dll	35592
tools/net10.0/any/de/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	52048
tools/net10.0/any/de/Microsoft.CodeAnalysis.CSharp.resources.dll	485672
tools/net10.0/any/de/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/de/Microsoft.CodeAnalysis.Features.resources.dll	207144
tools/net10.0/any/de/Microsoft.CodeAnalysis.resources.dll	42832
tools/net10.0/any/de/Microsoft.CodeAnalysis.Scripting.resources.dll	18216
tools/net10.0/any/de/Microsoft.CodeAnalysis.VisualBasic.resources.dll	318760
tools/net10.0/any/de/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24912
tools/net10.0/any/de/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17192
tools/net10.0/any/de/Microsoft.CodeAnalysis.Workspaces.resources.dll	42280
tools/net10.0/any/es/FSharp.Core.resources.dll	34568
tools/net10.0/any/es/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	52048
tools/net10.0/any/es/Microsoft.CodeAnalysis.CSharp.resources.dll	474920
tools/net10.0/any/es/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/es/Microsoft.CodeAnalysis.Features.resources.dll	204072
tools/net10.0/any/es/Microsoft.CodeAnalysis.resources.dll	42280
tools/net10.0/any/es/Microsoft.CodeAnalysis.Scripting.resources.dll	17704
tools/net10.0/any/es/Microsoft.CodeAnalysis.VisualBasic.resources.dll	313640
tools/net10.0/any/es/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24912
tools/net10.0/any/es/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17232
tools/net10.0/any/es/Microsoft.CodeAnalysis.Workspaces.resources.dll	41768
tools/net10.0/any/fr/FSharp.Core.resources.dll	35080
tools/net10.0/any/fr/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	52520
tools/net10.0/any/fr/Microsoft.CodeAnalysis.CSharp.resources.dll	488784
tools/net10.0/any/fr/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/fr/Microsoft.CodeAnalysis.Features.resources.dll	211240
tools/net10.0/any/fr/Microsoft.CodeAnalysis.resources.dll	42832
tools/net10.0/any/fr/Microsoft.CodeAnalysis.Scripting.resources.dll	18216
tools/net10.0/any/fr/Microsoft.CodeAnalysis.VisualBasic.resources.dll	321320
tools/net10.0/any/fr/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24912
tools/net10.0/any/fr/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17744
tools/net10.0/any/fr/Microsoft.CodeAnalysis.Workspaces.resources.dll	42792
tools/net10.0/any/it/FSharp.Core.resources.dll	34568
tools/net10.0/any/it/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	52008
tools/net10.0/any/it/Microsoft.CodeAnalysis.CSharp.resources.dll	481064
tools/net10.0/any/it/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17192
tools/net10.0/any/it/Microsoft.CodeAnalysis.Features.resources.dll	206120
tools/net10.0/any/it/Microsoft.CodeAnalysis.resources.dll	42792
tools/net10.0/any/it/Microsoft.CodeAnalysis.Scripting.resources.dll	18256
tools/net10.0/any/it/Microsoft.CodeAnalysis.VisualBasic.resources.dll	318248
tools/net10.0/any/it/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24912
tools/net10.0/any/it/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17704
tools/net10.0/any/it/Microsoft.CodeAnalysis.Workspaces.resources.dll	42320
tools/net10.0/any/ja/FSharp.Core.resources.dll	37136
tools/net10.0/any/ja/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	54568
tools/net10.0/any/ja/Microsoft.CodeAnalysis.CSharp.resources.dll	530768
tools/net10.0/any/ja/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/ja/Microsoft.CodeAnalysis.Features.resources.dll	217424
tools/net10.0/any/ja/Microsoft.CodeAnalysis.resources.dll	45352
tools/net10.0/any/ja/Microsoft.CodeAnalysis.Scripting.resources.dll	18216
tools/net10.0/any/ja/Microsoft.CodeAnalysis.VisualBasic.resources.dll	354088
tools/net10.0/any/ja/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24912
tools/net10.0/any/ja/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17744
tools/net10.0/any/ja/Microsoft.CodeAnalysis.Workspaces.resources.dll	44880
tools/net10.0/any/ko/FSharp.Core.resources.dll	36152
tools/net10.0/any/ko/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	50984
tools/net10.0/any/ko/Microsoft.CodeAnalysis.CSharp.resources.dll	486736
tools/net10.0/any/ko/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/ko/Microsoft.CodeAnalysis.Features.resources.dll	202024
tools/net10.0/any/ko/Microsoft.CodeAnalysis.resources.dll	42792
tools/net10.0/any/ko/Microsoft.CodeAnalysis.Scripting.resources.dll	18256
tools/net10.0/any/ko/Microsoft.CodeAnalysis.VisualBasic.resources.dll	323920
tools/net10.0/any/ko/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24872
tools/net10.0/any/ko/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17704
tools/net10.0/any/ko/Microsoft.CodeAnalysis.Workspaces.resources.dll	42280
tools/net10.0/any/pl/FSharp.Core.resources.dll	35632
tools/net10.0/any/pl/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	52008
tools/net10.0/any/pl/Microsoft.CodeAnalysis.CSharp.resources.dll	488272
tools/net10.0/any/pl/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17232
tools/net10.0/any/pl/Microsoft.CodeAnalysis.Features.resources.dll	203600
tools/net10.0/any/pl/Microsoft.CodeAnalysis.resources.dll	42792
tools/net10.0/any/pl/Microsoft.CodeAnalysis.Scripting.resources.dll	18216
tools/net10.0/any/pl/Microsoft.CodeAnalysis.VisualBasic.resources.dll	328528
tools/net10.0/any/pl/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24872
tools/net10.0/any/pl/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17744
tools/net10.0/any/pl/Microsoft.CodeAnalysis.Workspaces.resources.dll	42280
tools/net10.0/any/pt-BR/FSharp.Core.resources.dll	34056
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	51536
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.CSharp.resources.dll	466216
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17192
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.Features.resources.dll	202024
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.resources.dll	41768
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.Scripting.resources.dll	17744
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.VisualBasic.resources.dll	308008
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24872
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17232
tools/net10.0/any/pt-BR/Microsoft.CodeAnalysis.Workspaces.resources.dll	41768
tools/net10.0/any/ru/FSharp.Core.resources.dll	41784
tools/net10.0/any/ru/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	61224
tools/net10.0/any/ru/Microsoft.CodeAnalysis.CSharp.resources.dll	640848
tools/net10.0/any/ru/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17704
tools/net10.0/any/ru/Microsoft.CodeAnalysis.Features.resources.dll	268072
tools/net10.0/any/ru/Microsoft.CodeAnalysis.resources.dll	51496
tools/net10.0/any/ru/Microsoft.CodeAnalysis.Scripting.resources.dll	19280
tools/net10.0/any/ru/Microsoft.CodeAnalysis.VisualBasic.resources.dll	411432
tools/net10.0/any/ru/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	26920
tools/net10.0/any/ru/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17704
tools/net10.0/any/ru/Microsoft.CodeAnalysis.Workspaces.resources.dll	47400
tools/net10.0/any/runtimes/win/lib/net10.0/System.Diagnostics.EventLog.dll	176912
tools/net10.0/any/runtimes/win/lib/net10.0/System.Diagnostics.EventLog.Messages.dll	801040
tools/net10.0/any/tr/FSharp.Core.resources.dll	34096
tools/net10.0/any/tr/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	50472
tools/net10.0/any/tr/Microsoft.CodeAnalysis.CSharp.resources.dll	462120
tools/net10.0/any/tr/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17192
tools/net10.0/any/tr/Microsoft.CodeAnalysis.Features.resources.dll	197928
tools/net10.0/any/tr/Microsoft.CodeAnalysis.resources.dll	41256
tools/net10.0/any/tr/Microsoft.CodeAnalysis.Scripting.resources.dll	17704
tools/net10.0/any/tr/Microsoft.CodeAnalysis.VisualBasic.resources.dll	304464
tools/net10.0/any/tr/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24872
tools/net10.0/any/tr/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17192
tools/net10.0/any/tr/Microsoft.CodeAnalysis.Workspaces.resources.dll	41256
tools/net10.0/any/zh-Hans/FSharp.Core.resources.dll	32528
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	48424
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.CSharp.resources.dll	411432
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17192
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.Features.resources.dll	176936
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.resources.dll	38696
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.Scripting.resources.dll	17744
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.VisualBasic.resources.dll	277840
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24400
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17192
tools/net10.0/any/zh-Hans/Microsoft.CodeAnalysis.Workspaces.resources.dll	39208
tools/net10.0/any/zh-Hant/FSharp.Core.resources.dll	32008
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.CSharp.Features.resources.dll	48424
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.CSharp.resources.dll	410920
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.CSharp.Workspaces.resources.dll	17192
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.Features.resources.dll	178472
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.resources.dll	38736
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.Scripting.resources.dll	17704
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.VisualBasic.resources.dll	277288
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.VisualBasic.Workspaces.resources.dll	24360
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.Workspaces.MSBuild.resources.dll	17192
tools/net10.0/any/zh-Hant/Microsoft.CodeAnalysis.Workspaces.resources.dll	39208
CHANGELOG.md	39649
README.md	7018
.signature.p7s	12985
""".trimIndent().lineSequence().associate { row -> val parts = row.split('\t'); require(parts.size == 2); parts[0] to parts[1].toLong() }.also {
        require(it.size == MAX_FILES && it.values.sum() == MAX_TREE_BYTES)
    }
}

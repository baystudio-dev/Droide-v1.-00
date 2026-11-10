package com.baystudio.droide.core

internal object LocalUbuntuCSharpSdkPolicy {
    const val FAMILY = "runtime.csharp-sdk"
    const val VERSION = "10.0.402"
    const val RUNTIME_VERSION = "10.0.13"
    const val COMMAND = "droide-csharp-sdk"
    const val ENTRY = "dotnet"
    const val KEY = "$FAMILY@$VERSION"
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val SDK_ROOT = "$ROOT/payload/tree"
    const val EXECUTABLE = "$SDK_ROOT/dotnet"
    const val ASSET = "dotnet-sdk-10.0.402-linux-arm64.tar.gz"
    const val ASSET_URL = "https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.402/$ASSET"
    const val SHA512 = "db85ae85527251fbb1290720ac3bf324bb1d9eea74516ef291ed4bb1f8ff6ec63a17593367f49b456115e8b24ead6d0c2ece2801e501f36a6cd4444ed01a498f"
    val recipe = CSharpInstallRecipe(FAMILY, VERSION, COMMAND, CSharpArtifactRole.ORIGINAL_SDK, ASSET_URL, ASSET,
        CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA512, SHA512), null,
        1_073_741_824L, 32768, 4_294_967_296L, ENTRY,
        "https://dotnetcli.blob.core.windows.net/dotnet/release-metadata/10.0/releases.json")
    val required = setOf(ENTRY, "host/fxr/$RUNTIME_VERSION/libhostfxr.so", "shared/Microsoft.NETCore.App/$RUNTIME_VERSION/libhostpolicy.so",
        "shared/Microsoft.NETCore.App/$RUNTIME_VERSION/libcoreclr.so", "shared/Microsoft.NETCore.App/$RUNTIME_VERSION/System.Private.CoreLib.dll",
        "sdk/$VERSION/MSBuild.dll", "sdk/$VERSION/dotnet.dll", "sdk/$VERSION/MSBuild.runtimeconfig.json", "sdk/$VERSION/MSBuild.deps.json",
        "LICENSE.txt", "ThirdPartyNotices.txt")
}

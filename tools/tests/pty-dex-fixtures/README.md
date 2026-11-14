# PTY DEX binding fixtures

`test_pty_dex_binding.py` creates small standard-DEX fixtures in memory. These are
parser/mutation fixtures only and never stand in for a production D8 reference.
The fixture builder recalculates DEX SHA-1 and Adler-32 after each mutation, so
negative tests exercise structural/content checks rather than only checksums.

Real compiler integration tests use local, already restored inputs only:

- `R46_TEST_R8_JAR`: project R8 9.1.29, SHA-256
  `9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a`
- `R46_TEST_ANDROID_JAR`: pinned API 36 android.jar
- `R46_TEST_JAVA_HOME`: pinned recovered Temurin 17.0.20.1+1
- `R46_REQUIRE_D8_TESTS=1`: fail instead of skip when a prerequisite is missing

Those tests run actual javac/D8 in new temporary roots, compare current candidate
PTY code with frozen baseline bodies, compile unrelated classes into a different
pool/file layout, and mutate real D8-produced references/catches. They do not
build an APK, download dependencies, execute Android code, or approve a candidate.
A complete acceptance report must state whether these tests ran, not merely cite
synthetic tests as proof of real D8 compatibility.

An additional explicitly exploratory route, `SDKFixtureD8Tests`, accepts only the
restored SDK 36.0.0 D8 8.10.9-dev through `R46_TEST_SDK_D8_JAR`, SHA-256
`4097ff9c46c185c6e7214da7fe9b1befb5adeea5cc9ca349270e0249904f9240`.
It checks the parser against genuine compiler output while exact project R8 is
unavailable. It does not satisfy the production-R8 acceptance gate. Production
`build_fresh_reference` rejects that SDK compiler unconditionally.

The real compiler test classes include an additional actual-AAR test: it requires
`verify_derivative_inputs` success, reads `classes.jar` directly from both the
admitted derivative and frozen historical AARs, compiles each and rejects the
stale historical implementation. With exact project R8, it also calls the full
mandatory-admission/fresh-reference public API on a DEX-only test container.
That test container is never a signed/installable APK or runtime validation.

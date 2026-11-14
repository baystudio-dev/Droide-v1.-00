# R64 CLI Exit receipt host fixtures

These fixtures compile the complete, unmodified-at-test-time production DroideCliServer, CliEndpointPublication, CliConnectionLifetime, CliProgressRelay, PackageProgressObserver, and DroideCliWireProtocol files. Android Context, Process, Log, LocalSocket, and LocalServerSocket are explicit JVM boundary doubles. Authority, tool execution, installer, and admission are bounded unrelated fixture contracts. No package transaction or native binary runs.

The new three lifecycle tests use real temporary endpoint files and injected listener close/construction races. They cover normal Exit, retained failed endpoint withdrawal with retry, and a listener constructed after cancellation whose final close fails. Existing CliConnectionLifetimeTest uses actual loopback TCP sockets; those tests are not Android LocalSocket tests. Existing endpoint/progress/protocol tests are run too.

Run from the R64 workspace with the already-present R44 toolchain:

    R64_CLI_EVIDENCE=cli-exit-my-run python tools/host-regressions/run_cli_exit_boundary.py

The evidence directory must be new. The runner writes commands, source/tool SHA-256 identities, logs, exit codes, and unchanged-input checks. R64_CLI_SOURCE can select a preserved pre-fix DroideCliServer source for a negative control. It never downloads dependencies or builds an APK.

The separate Kotlin boundary compilation uses Android API36 android.jar SHA-256 d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a. The runner records full javap output, including BAKLAVA=36, before compiling. This is targeted API36 type compilation, not full-app compilation or Android execution. The recorded toolchain uses Kotlin 2.0.21, coroutines 1.6.4, and serialization 1.6.2; the serialization jars are classpath-only here.

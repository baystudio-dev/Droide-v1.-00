package com.baystudio.droide.core

/**
 * Ubuntu's required python3 supplies a bounded stdio event loop. Each request owns one
 * native CLI connection: terminating that process closes its socket and cancels the
 * host operation through CliConnectionLifetime. IDs never cross stdio connections.
 */
internal object ExternalIdeMcpTransport {
    internal val pythonScript = """
        import asyncio
        import json
        import signal
        import sys

        MAX_REQUEST = 4096
        MAX_RESPONSE = 65536
        MAX_IN_FLIGHT = 16

        class OutputProtocol(asyncio.Protocol):
            def __init__(self):
                self.ready = asyncio.Event()
                self.ready.set()
                self.closed = False
            def pause_writing(self):
                self.ready.clear()
            def resume_writing(self):
                self.ready.set()
            def connection_lost(self, error):
                self.closed = True
                self.ready.set()

        def valid_id(value):
            return (isinstance(value, str) and len(value) <= 128 or
                type(value) is int and -(2**63) <= value < 2**63)

        async def drain(task):
            # Finish ownership transfer/reaping even if another signal arrives.
            while not task.done():
                try:
                    await asyncio.shield(task)
                except asyncio.CancelledError:
                    pass
            return task.result()

        async def main():
            executable, token = sys.argv[1:]
            loop = asyncio.get_running_loop()
            owner = asyncio.current_task()
            for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                loop.add_signal_handler(signum, owner.cancel)
            reader = asyncio.StreamReader(limit=MAX_REQUEST + 1)
            input_transport, _ = await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin.buffer)
            protocol = OutputProtocol()
            output, _ = await loop.connect_write_pipe(lambda: protocol, sys.stdout.buffer)
            output.set_write_buffer_limits(high=MAX_RESPONSE, low=MAX_RESPONSE // 4)
            output_lock = asyncio.Lock()
            pending = {}
            failure = None

            async def emit(payload):
                async with output_lock:
                    await protocol.ready.wait()
                    if protocol.closed:
                        raise BrokenPipeError("MCP output was closed")
                    output.write(payload)
                    await protocol.ready.wait()
                    if protocol.closed:
                        raise BrokenPipeError("MCP output was closed")

            async def reject(request_id, code, message):
                await emit((json.dumps({"jsonrpc": "2.0", "id": request_id,
                    "error": {"code": code, "message": message}}) + "\n").encode("utf-8"))

            async def invoke(line):
                # Shield creation until its process handle is owned, including cancellation
                # between fork/exec and create_subprocess_exec returning to this task.
                creation = asyncio.create_task(asyncio.create_subprocess_exec(executable,
                    "ide", "rpc", token, line, stdout=asyncio.subprocess.PIPE,
                    stderr=sys.stderr, limit=MAX_RESPONSE))
                process = None
                try:
                    process = await asyncio.shield(creation)
                    try:
                        await process.stdout.readexactly(MAX_RESPONSE + 1)
                    except asyncio.IncompleteReadError as end:
                        payload = end.partial
                    else:
                        raise ValueError("IDE response exceeds transport capacity")
                    if await process.wait():
                        raise RuntimeError("IDE connection failed; reconnect to the workspace")
                    if payload:
                        await emit(payload)
                finally:
                    async def reap():
                        child = process or await creation
                        async def discard_output():
                            while await child.stdout.read(8192):
                                pass
                        # A full stdout pipe can otherwise keep Process.wait suspended
                        # even after kill. Drain with constant memory while reaping.
                        discard = asyncio.create_task(discard_output())
                        if child.returncode is None:
                            try:
                                child.terminate()
                            except ProcessLookupError:
                                pass
                            try:
                                await asyncio.wait_for(child.wait(), timeout=2)
                            except asyncio.TimeoutError:
                                try:
                                    child.kill()
                                except ProcessLookupError:
                                    pass
                                await child.wait()
                        await discard
                    await drain(asyncio.create_task(reap()))

            def finished(request_id, task):
                nonlocal failure
                if pending.get(request_id, (None, None))[0] is task:
                    del pending[request_id]
                if not task.cancelled() and task.exception() is not None:
                    failure = task.exception()
                    owner.cancel()

            try:
                while True:
                    raw = await reader.readline()
                    if not raw:
                        break
                    raw = raw.removesuffix(b"\n").removesuffix(b"\r")
                    if len(raw) > MAX_REQUEST:
                        raise ValueError("IDE request exceeds 4096 UTF-8 bytes")
                    line = raw.decode("utf-8", errors="strict")
                    try:
                        request = json.loads(line)
                    except (ValueError, RecursionError):
                        await reject(None, -32700, "Invalid bounded JSON request")
                        continue
                    if (not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or
                        not isinstance(request.get("method"), str) or not request["method"].strip() or
                        "id" in request and not valid_id(request["id"])):
                        await reject(None, -32600, "Invalid JSON-RPC request")
                        continue
                    if "id" not in request:
                        params = request.get("params")
                        if request["method"] == "notifications/cancelled" and isinstance(params, dict):
                            request_id = params.get("requestId")
                            active = pending.get(request_id) if valid_id(request_id) else None
                            if active and active[1] != "initialize":
                                active[0].cancel()
                        # Notifications never execute tools and do not need a subprocess.
                        continue
                    request_id = request["id"]
                    if request_id in pending:
                        await reject(request_id, -32600, "Duplicate active JSON-RPC request ID")
                    elif len(pending) >= MAX_IN_FLIGHT:
                        await reject(request_id, -32000, "Too many active IDE requests; wait for completion")
                    else:
                        task = asyncio.create_task(invoke(line))
                        pending[request_id] = (task, request["method"])
                        task.add_done_callback(lambda task, key=request_id: finished(key, task))
            finally:
                input_transport.close()
                remaining = [task for task, _ in pending.values()]
                for task in remaining:
                    task.cancel()
                await drain(asyncio.gather(*remaining, return_exceptions=True))
                output.close()
                if failure is not None:
                    raise failure

        try:
            asyncio.run(main())
        except (asyncio.CancelledError, KeyboardInterrupt):
            pass
        except Exception as error:
            print(str(error), file=sys.stderr)
            sys.exit(2 if isinstance(error, ValueError) else 1)
    """.trimIndent()

    // Only the constant program is shell-quoted. Executable and generation remain separate argv.
    val stdioScript = "exec /usr/bin/python3 -u -c '" + pythonScript.replace("'", "'\\''") + "' \"\$1\" \"\$2\""
}

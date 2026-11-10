#!/usr/bin/env escript
%% coding: utf-8
%%! +S1 +A0 +P 1024 +Q 1024 -boot no_dot_erlang
%% App-authored fixed report-only probe; selected execution requires explicit authority.
%% Invoke as an input to the captured escript, with ESCRIPT_EMULATOR fixed.
%% The production equivalent must use the same body for its fixed erl -eval.
main([]) ->
    Root = code:root_dir(),
    Major = erlang:system_info(otp_release),
    {ok, FullBytes} = file:read_file(filename:join([Root, "releases", Major, "OTP_VERSION"])),
    Fields = [
        {"otp_release", Major},
        {"otp_version_file", binary_to_list(FullBytes)},
        {"erts_version", erlang:system_info(version)},
        {"system_version", erlang:system_info(system_version)},
        {"architecture", erlang:system_info(system_architecture)},
        {"root_dir", Root},
        {"lib_dir", code:lib_dir()},
        {"kernel_dir", code:lib_dir(kernel)},
        {"stdlib_dir", code:lib_dir(stdlib)},
        {"application_module", code:which(application)},
        {"erl_scan_module", code:which(erl_scan)},
        {"compiler_dir", case code:lib_dir(compiler) of {error, _} -> "unavailable"; C -> C end},
        {"compile_module", case code:which(compile) of non_existing -> "unavailable"; M -> M end}
    ],
    io:format("DROIDE_ELP_RUNTIME_V1~n", []),
    lists:foreach(fun({Key, Value}) ->
        true = is_list(Value),
        Encoded = base64:encode(unicode:characters_to_binary(Value)),
        io:format("~s\t~s~n", [Key, Encoded])
    end, Fields),
    io:format("END_DROIDE_ELP_RUNTIME_V1~n", []);
main(_) -> halt(64).

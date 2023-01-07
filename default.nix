with import <nixpkgs> {};
stdenv.mkDerivation rec {
    name = "xcl";
    env = buildEnv {
        name = name;
        paths = buildInputs;
    };
    buildInputs = [
        nodejs
        postgresql
        sqlite
        watchexec
    ];
    shellHook = ''
        export PATH=$PATH:$(npm bin)
        if ! (command -v shadow-cljs &>/dev/null); then
            npm install
        fi

        alias watch="shadow-cljs watch"
        alias watch-metadata="shadow-cljs watch macchiato crud-frontend"
        alias watch-indexer="shadow-cljs watch macchiato indexer-frontend"
        alias we-m="watchexec --restart --no-ignore --watch build/ node build/macchiato-server.js"
        alias we-node="watchexec --restart --no-ignore --watch build/ node"

        test-jsonrpc-echo() {
            curl -X POST -H 'Content-Type: application/json' http://localhost:23120/rpc -d '{"jsonrpc":"2.0","id":1,"method":"echo","params":{"protocol":"TEST","directive":"foo"}}'
        }

        cat ${__curPos.file} | grep '^ *alias'
    '' + ''
        echo-shortcuts ${__curPos.file}
    '';
}

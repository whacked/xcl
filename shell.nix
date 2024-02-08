{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
  buildInputs = [
    pkgs.nodejs
    pkgs.postgresql
    pkgs.sqlite
    pkgs.watchexec
  ];  # join lists with ++

  nativeBuildInputs = [
    ~/setup/bash/nix_shortcuts.nix.sh
    ~/setup/bash/node_shortcuts.sh
  ];

  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [pkgs.libuuid];

  shellHook = ''
    activate-node-env
    if ! (command -v shadow-cljs &>/dev/null); then
        npm install
    fi

    alias watch="shadow-cljs watch"
    alias watch-metadata="shadow-cljs watch macchiato crud-frontend"
    alias watch-indexer="shadow-cljs watch macchiato indexer-frontend"
    alias we-m="watchexec --restart --no-ignore --watch build/ node build/macchiato-server.js"
    alias we-node="watchexec --restart --no-ignore --watch build/ node"
    alias start-content-server="node build/node-content-server.js"

    test-jsonrpc-echo() {
        curl -X POST -H 'Content-Type: application/json' http://localhost:23120/rpc -d '{"jsonrpc":"2.0","id":1,"method":"echo","params":{"protocol":"TEST","directive":"foo"}}'
    }
  '' + ''
    echo-shortcuts ${__curPos.file}
  '';
}


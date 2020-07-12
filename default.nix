with import <nixpkgs> {};
stdenv.mkDerivation rec {
    name = "xcl";
    env = buildEnv {
        name = name;
        paths = buildInputs;
    };
    buildInputs = [
        nodejs-10_x
        watchexec
        sqlite
    ];
    shellHook = ''
        export PATH=$PATH:$(npm bin)
        if ! (command -v shadow-cljs &>/dev/null); then
            npm install
        fi

        alias watch="shadow-cljs watch"
        alias we-m="watchexec --restart --no-ignore --watch build/ node build/macchiato-server.js"
    '';
}


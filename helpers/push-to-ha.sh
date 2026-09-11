#!/usr/bin/env bash
# Compatibilidade: a publicacao no HA virou parte do proprio build.sh, junto com
# a instalacao no carro. Este script so repassa, para nao quebrar quem (ou o que)
# ainda chama pelo nome antigo.
#
#   ./build.sh                 # compila, publica no HA e instala no carro
#   NO_DEPLOY=1 ./build.sh     # so compila
#   HA_HOST=... CAR=... ./build.sh
exec "$(dirname "$0")/../drivemem/build.sh" "$@"

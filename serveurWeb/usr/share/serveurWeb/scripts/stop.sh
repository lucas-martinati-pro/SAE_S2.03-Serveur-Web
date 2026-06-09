#!/bin/bash
# Script d'arrêt pour le serveur web

# Détecter dynamiquement le dossier où se trouve le script pour être portable
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"
PID_FILE="$BASE_DIR/run/myweb.pid"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" >/dev/null 2>&1; then
        kill "$PID"
        # Attendre que le processus s'arrête
        while kill -0 "$PID" >/dev/null 2>&1; do
            sleep 0.1
        done
    fi
    rm -f "$PID_FILE"
fi

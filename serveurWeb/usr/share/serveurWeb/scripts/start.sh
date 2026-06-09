#!/bin/bash
# Script de démarrage pour le serveur web

# Détecter dynamiquement le dossier où se trouve le script pour être portable
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"

# S'assurer que le répertoire de run existe
mkdir -p "$BASE_DIR/run"

# Lancement du serveur en tâche de fond
cd "$BASE_DIR"
java -cp out:bin ServerMain > /dev/null 2>&1 &

# Attendre que le PID soit écrit (maximum 5 secondes)
for i in {1..10}; do
    if [ -f "$BASE_DIR/run/myweb.pid" ]; then
        exit 0
    fi
    sleep 0.5
done

exit 1

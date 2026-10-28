#!/usr/bin/env bash
# Atalho de desenvolvimento: usa a JDK 21 local e o Maven em cache do wrapper.
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21.0.12.1+1}"
exec "$HOME/.m2/wrapper/dists/apache-maven-3.9.9/3477a4f1/bin/mvn" "$@"

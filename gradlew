#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME" || exit 1
exec java -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"

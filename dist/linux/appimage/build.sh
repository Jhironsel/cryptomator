#!/bin/bash

cd $(dirname $0)
REVISION_NO=`git rev-list --count HEAD`

# check preconditions
if [ -z "${JAVA_HOME}" ]; then echo "JAVA_HOME not set. Run using JAVA_HOME=/path/to/jdk ./build.sh"; exit 1; fi
command -v mvn >/dev/null 2>&1 || { echo >&2 "mvn not found."; exit 1; }
command -v curl >/dev/null 2>&1 || { echo >&2 "curl not found."; exit 1; }

VERSION=$(mvn -f ../../../pom.xml help:evaluate -Dexpression=project.version -q -DforceStdout)
SEMVER_STR=${VERSION}

mvn -f ../../../pom.xml versions:set -DnewVersion=${SEMVER_STR}

# compile
mvn -B -f ../../../pom.xml clean package -Plinux -DskipTests
cp ../../../LICENSE.txt ../../../target
cp ../launcher.sh ../../../target
cp ../../../target/cryptomator-*.jar ../../../target/mods

# add runtime
${JAVA_HOME}/bin/jlink \
    --verbose \
    --output runtime \
    --module-path "${JAVA_HOME}/jmods" \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.naming,java.net.http,java.scripting,java.sql,java.xml,javafx.base,javafx.graphics,javafx.controls,javafx.fxml,jdk.unsupported,jdk.crypto.ec,jdk.security.auth,jdk.accessibility,jdk.management.jfr,jdk.zipfs \
    --strip-native-commands \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --compress=1

# create app dir
envsubst '${SEMVER_STR} ${REVISION_NUM}' < ../launcher-gtk2.properties > launcher-gtk2.properties
${JAVA_HOME}/bin/jpackage \
    --verbose \
    --type app-image \
    --runtime-image runtime \
    --input ../../../target/libs \
    --module-path ../../../target/mods \
    --module org.cryptomator.desktop/org.cryptomator.launcher.Cryptomator \
    --dest appdir \
    --name Cryptomator \
    --vendor "Skymatic GmbH" \
    --java-options "--enable-preview" \
    --java-options "--enable-native-access=org.cryptomator.jfuse.linux.amd64,org.cryptomator.jfuse.linux.aarch64" \
    --copyright "(C) 2016 - 2023 Skymatic GmbH" \
    --java-options "-Xss5m" \
    --java-options "-Xmx256m" \
    --app-version "${VERSION}.${REVISION_NO}" \
    --java-options "-Dfile.encoding=\"utf-8\"" \
    --java-options "-Dcryptomator.logDir=\"~/.local/share/Cryptomator/logs\"" \
    --java-options "-Dcryptomator.pluginDir=\"~/.local/share/Cryptomator/plugins\"" \
    --java-options "-Dcryptomator.settingsPath=\"~/.config/Cryptomator/settings.json:~/.Cryptomator/settings.json\"" \
    --java-options "-Dcryptomator.p12Path=\"~/.config/Cryptomator/key.p12\"" \
    --java-options "-Dcryptomator.ipcSocketPath=\"~/.config/Cryptomator/ipc.socket\"" \
    --java-options "-Dcryptomator.mountPointsDir=\"~/.local/share/Cryptomator/mnt\"" \
    --java-options "-Dcryptomator.showTrayIcon=false" \
    --java-options "-Dcryptomator.buildNumber=\"appimage-${REVISION_NO}\"" \
    --add-launcher cryptomator-gtk2=launcher-gtk2.properties \
    --resource-dir ../resources

# transform AppDir
mv appdir/Cryptomator Cryptomator.AppDir
cp -r resources/AppDir/* Cryptomator.AppDir/
envsubst '${REVISION_NO}' < resources/AppDir/bin/cryptomator.sh > Cryptomator.AppDir/bin/cryptomator.sh
cp ../common/org.cryptomator.Cryptomator256.png Cryptomator.AppDir/usr/share/icons/hicolor/256x256/apps/org.cryptomator.Cryptomator.png
cp ../common/org.cryptomator.Cryptomator512.png Cryptomator.AppDir/usr/share/icons/hicolor/512x512/apps/org.cryptomator.Cryptomator.png
cp ../common/org.cryptomator.Cryptomator.svg Cryptomator.AppDir/usr/share/icons/hicolor/scalable/apps/org.cryptomator.Cryptomator.svg
cp ../common/org.cryptomator.Cryptomator.desktop Cryptomator.AppDir/usr/share/applications/org.cryptomator.Cryptomator.desktop
cp ../common/org.cryptomator.Cryptomator.metainfo.xml Cryptomator.AppDir/usr/share/metainfo/org.cryptomator.Cryptomator.metainfo.xml
cp ../common/application-vnd.cryptomator.vault.xml Cryptomator.AppDir/usr/share/mime/packages/application-vnd.cryptomator.vault.xml
ln -s usr/share/icons/hicolor/scalable/apps/org.cryptomator.Cryptomator.svg Cryptomator.AppDir/org.cryptomator.Cryptomator.svg
ln -s usr/share/icons/hicolor/scalable/apps/org.cryptomator.Cryptomator.svg Cryptomator.AppDir/Cryptomator.svg
ln -s usr/share/icons/hicolor/scalable/apps/org.cryptomator.Cryptomator.svg Cryptomator.AppDir/.DirIcon
ln -s usr/share/applications/org.cryptomator.Cryptomator.desktop Cryptomator.AppDir/Cryptomator.desktop
ln -s bin/cryptomator.sh Cryptomator.AppDir/AppRun

# load AppImageTool
curl -L https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage -o /tmp/appimagetool.AppImage
chmod +x /tmp/appimagetool.AppImage

# create AppImage
/tmp/appimagetool.AppImage \
    Cryptomator.AppDir \
    cryptomator-${SEMVER_STR}-x86_64.AppImage  \
    -u 'gh-releases-zsync|cryptomator|cryptomator|latest|cryptomator-*-x86_64.AppImage.zsync'

echo ""
echo "Done. AppImage successfully created: cryptomator-${SEMVER_STR}-x86_64.AppImage"
echo ""
echo >&2 "To clean up, run: rm -rf Cryptomator.AppDir appdir jni runtime squashfs-root; rm launcher-gtk2.properties /tmp/appimagetool.AppImage"
echo ""
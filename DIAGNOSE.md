# Diagnóstico: "ocorreu um problema ao analisar o pacote"

O telefone reporta `INSTALL_FAILED_INVALID_APK`. O APK em si passou **todas** as
verificações mecânicas (integridade, alinhamento, assinatura em todos os níveis
de API, diff do manifesto, diff dos recursos, badging idêntico) e instala em
emuladores Android 11 e Android 15.

Para diagnosticar, preciso da mensagem exacta do `PackageManager`. Corre isto
com o telemóvel ligado por USB e com o *adb* instalado:

## 1. Instalar a partir do adb (a via determinística)

```bash
adb install caniOn_152_patched_20260822.apk
```

O `adb` imprime o motivo real, por exemplo:

```
Failure [INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2]
```

ou

```
Failure [INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: ...]
```

**Copia a linha `Failure [...]` na íntegra** — é o que falta.

## 2. Se preferires pelo instalador do telefone

```bash
adb logcat -c
# <agora faz a instalação pelo gestor de ficheiros e falha>
adb logcat -d | grep -iE "PackageManager|PackageInstaller|parsePackage"
```

## 3. Estado do telefone

```bash
adb shell getprop ro.build.version.release   # esperado: 15
adb shell getconf PAGE_SIZE                  # 4096 ou 16384
adb shell pm list packages | grep canion     # deve estar vazio
adb shell 'stat -c %s /sdcard/Download/caniOn_152_patched_20260822.apk'
```

O tamanho tem de ser exactamente `3718346`. Um valor menor significa que a
cópia foi interrompida.

## 4. Se `adb install` também falhar

Desinstala primeiro e volta a instalar:

```bash
adb uninstall emobility.canion
adb install caniOn_152_patched_20260822.apk
```

E se mesmo assim falhar, tenta a variante assinado só com v1+v2 (o esquema
exactamente igual ao do APK original):

```bash
adb install caniOn_152_patched_v1v2.apk
```

## Nota sobre o directório de dados

O app cria sozinho o directório `/sdcard/BT_CAN/` no primeiro arranque e lá
guarda a base de dados `btcan.db`. Se o log mostrar `SQLITE_CANTOPEN`, o
directório não está a ser criado — verifica:

```bash
adb shell ls -la /sdcard/BT_CAN
```

E para activar o log em ficheiro:

```bash
adb shell touch /sdcard/BT_CAN/log_main.txt
adb shell am force-stop emobility.canion
adb shell am start -n emobility.canion/.main
```

Vê [DEBUGGING.md](DEBUGGING.md) para o quadro completo.

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

Depois de instalado, o app abre a base de dados em
`/storage/emulated/0/BT_CAN/btcan.db`. Num telefone novo esse directório não
existe e o app mostra um ecrã vazio com `SQLITE_CANTOPEN` no log. Cria-o:

```bash
adb shell mkdir -p /sdcard/BT_CAN
```

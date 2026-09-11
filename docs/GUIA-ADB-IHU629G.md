# Habilitar ADB no Geely IHU629G (macOS)

Testado e aplicado com sucesso em 02/08/2026 numa unidade `IHU629G` versão
**1111** (Android 9 / SDK 28).

Baseado no vídeo do **Jean na Estrada**: https://youtu.be/T-77g9hn5LU
Os arquivos são dele; este texto adapta o processo para **macOS** e detalha o
que fazer quando dá errado.

⚠️ **Antes de tudo:** confira sua versão em **Meu carro > Versão** e baixe a
pasta certa — 1111 ou 1114.

---

# PARTE 1 — O procedimento

## Passo 0: Baixar os arquivos

Abra o Drive **Geely Ex2 - Jean na Estrada**:
https://drive.google.com/drive/folders/1RPzQlNtSc0YC_rpFQf_IIgdB6WvejiLl?usp=drive_link

Tem três pastas:

| Pasta | O que é |
|---|---|
| `Desbloqueio versao 1111` | Patch para a versão **1111** |
| `Desbloqueio 1114` | Patch para a versão **1114** |
| `Apps para o carro` | APKs (usados no Passo 5) |

1. Entre na pasta da **sua versão**. Tem um zip só lá dentro — baixe ele.
   (Na 1111: `3C6025_SW0E22H0128H111100000_user_995.zip`)
2. Extraia o zip. Vai criar uma pasta com esse mesmo nome comprido.
3. **Pare por aqui.** Não abra, não extraia mais nada. Essa pasta vai inteira
   para o pendrive no próximo passo.

Dentro dela já existe `OS/update.zip`. É assim que tem que ficar — o
`update.zip` continua zipado.

## Passo 1: Formatar o pendrive (no Mac)

Espete o pendrive e descubra o identificador dele:

```bash
diskutil list external
```

Resposta parecida com esta:

```
/dev/disk4 (external, physical):
   #:                       TYPE NAME              SIZE       IDENTIFIER
   0:     FDisk_partition_scheme                   *7.8 GB    disk4
   1:                 DOS_FAT_32 MEU_PENDRIVE      7.8 GB     disk4s1
```

O identificador é o **`disk4`** da primeira linha — no seu Mac pode ser `disk2`,
`disk5`, qualquer número. Confira pelo **tamanho** e pelo **nome** do pendrive
para ter certeza de que é ele.

> 🚨 **O comando abaixo apaga TUDO no disco que você escolher.** Se errar o
> número, formata o disco errado e não tem como desfazer.

Formate (**substitua `SEU_DISCO_AQUI`** pelo identificador que você achou, ex.
`disk4`):

```bash
diskutil eraseDisk FAT32 ATUALIZA MBRFormat /dev/SEU_DISCO_AQUI
```

O pendrive vai montar como `/Volumes/ATUALIZA`.

## Passo 2: Copiar os arquivos

Copie a pasta baixada **inteira**, sem renomear e sem extrair nada:

```bash
cp -R "3C6025_SW0E22H0128H111100000_user_995" /Volumes/ATUALIZA/
```

Tem que ficar assim:

```
ATUALIZA
└── 3C6025_SW0E22H0128H111100000_user_995
    └── OS
        └── update.zip          ← continua zipado
```

> Versão 1114: a pasta é `3C6025_SW0E22H0306H111400000_user_995`.

Limpe os metadados do macOS, confira se a cópia bateu e ejete:

```bash
mdutil -i off /Volumes/ATUALIZA
dot_clean -m /Volumes/ATUALIZA
find /Volumes/ATUALIZA -name "._*" -delete
find /Volumes/ATUALIZA -name ".DS_Store" -delete

shasum -a 256 "3C6025_SW0E22H0128H111100000_user_995/OS/update.zip" \
              "/Volumes/ATUALIZA/3C6025_SW0E22H0128H111100000_user_995/OS/update.zip"

diskutil eject /dev/SEU_DISCO_AQUI
```

Os dois checksums têm que ser iguais.

## Passo 3: Aplicar no carro

1. Ligue o carro. Desligue o Bluetooth.
2. Insira o pendrive. Espere **"um dispositivo foi inserido"**.
3. No discador do telefone, digite a senha dinâmica:

   **`#*` + (mês + 10) + dia + hora (formato 12h)**

   Exemplo — 02/08/2026 às 21h → `#*180209`
   (mês+10 = 8+10 = **18** · dia = **02** · hora 21h em 12h = **09**)

   ⚠️ A hora é o **último** campo, e em formato 12 horas (21h vira 09, 13h vira
   01). Cada campo tem dois dígitos.

4. Toque em **`U盘升级`** ("U-disk upgrade") — 4ª linha, coluna da direita.
   Procure o rótulo, não a posição.
5. A unidade reinicia no Android Recovery e aplica o patch.
6. Selecione **`Reboot system now`** (primeiro item, já destacado).

**Deu certo se aparecer:**

```
Result: added (2305 -> 2335 bytes)
PT  ativado
Done = ADB enabled - intentional stop, nothing wiped
```

**Ignore o erro no final** — é proposital, o próprio script avisa antes que é
normal e que nada foi apagado:

```
E:Error in /update/update.zip (status 7)
Installation aborted.
```

## Passo 4: Conectar o Mac ao carro (ADB)

**O Mac e o carro precisam estar na mesma rede Wi-Fi.** O Wi-Fi de casa resolve,
se a garagem tiver sinal.

⚠️ **A rede tem que ser 2.4 GHz.** A unidade não enxerga 5 GHz — rede com nome
tipo `..._5G` nem aparece na lista dela. Quase todo roteador emite as duas
bandas; conecte o carro na 2.4 GHz e o Mac em qualquer uma, contanto que seja o
mesmo roteador.

Se o carro não achar a rede, é porque o roteador junta as duas bandas num nome
só. Duas saídas:

- **Hotspot do celular** — mais rápido. No Android: banda 2.4 GHz, segurança
  WPA2 (não WPA3), nome e senha em ASCII simples.
- **Separar as bandas** nas configurações do roteador, criando um nome distinto
  para a 2.4 GHz.

### Conectar o carro na rede

A tela de Wi-Fi fica dentro do menu secreto:

1. No discador do telefone, digite a senha dinâmica de novo:
   **`#*` + (mês + 10) + dia + hora (12h)**

   ⚠️ **Recalcule.** A hora mudou desde o Passo 3 — se você aplicou o patch às
   21h e agora são 22h, o código é outro.

2. Toque em **`无线网络`** ("rede sem fio") — **última linha, canto inferior
   direito**.
3. Ligue o Wi-Fi na chavinha, escolha sua rede e digite a senha.

### Descobrir o IP do carro

Depois de conectado, pegue o IP em qualquer um destes:

- **Tela do carro:** aparece no diagnóstico, na coluna da esquerda, como
  `Wi-Fi IP`
- **Roteador:** lista de dispositivos conectados. A unidade aparece com nome de
  TV box (no meu caso, `SDMC Android TV Box DV8919`)
- **Hotspot Android:** lista de clientes conectados

> Se mostrar `Wi-Fi IP : 0.0.0.0`, ele associou mas não pegou endereço (DHCP).
> Esqueça a rede no carro e conecte de novo, ou reinicie o roteador/hotspot.

```bash
brew install android-platform-tools
adb connect 192.168.0.150:5555     # troque pelo IP do carro
adb devices -l
```

Esperado:

```
192.168.0.150:5555  device product:IHU629G model:IHU629G device:IHU629G
```

Pronto. Daqui pra frente não desligue o carro nem tire o Mac da rede — a conexão
cai e você refaz este passo (`adb connect` de novo).

> A tela de Wi-Fi do carro fica no menu secreto (a mesma senha dinâmica do Passo
> 3). Depois de instalar o **app de terceiros** (Passo 5), dá pra chegar nela pelo
> app, sem digitar senha nenhuma.

## Passo 5: Instalar os APKs

Os APKs estão na pasta **`Apps para o carro`** do mesmo Drive (Passo 0).

Um comando por app:

```bash
adb install -r NomeDoApp.apk
```

**Instale primeiro o app de terceiros**. É o único app de sistema
que vale a pena: dá atalho para a tela de Wi-Fi do menu secreto (sem digitar a
senha dinâmica) e libera configurações do veículo.

```bash
adb install -r NomeDoApp.apk
```

Pule *G Kit Wi-Fi*, *G Kit Temperature* e *Auto Setting AVAS Wi-Fi*.

Depois, **microG antes do ReVanced** — o resto tanto faz:

```bash
adb install -r Microg_6.1.4_Alx.apk
adb install -r Youtube_Revanced_20.14.43_Alx.apk
adb install -r Cx_File_Explorer_2.7.5-275_Alx.apk
adb install -r Firefox_151.0.4_Alx.apk
adb install -r Aurora_Store_4.7.5_Alx.apk
```

Spotify, VLC e Waze: busque no Aurora Store. Confira compatibilidade com
Android 9.

---

# PARTE 2 — Quando dá errado

## Erros na hora de aplicar o patch

### `ensure udisk plugged and update.zip exists under udisk JsbdUpgrade\OS directory`

Essa mensagem é **genérica** e não descreve o caminho real que o código procura.
Não crie pasta `JsbdUpgrade/` — testei, não funciona.

Na prática, é quase sempre a estrutura de pastas. Os dois erros comuns:

❌ Extrair o `update.zip` (ele fica zipado do jeito que veio)
❌ Copiar só a pasta `OS`, sem a pasta principal em volta

**Por que a pasta principal é obrigatória** — o log da unidade explica:

```
TestMain: version suffix = SW0E22H
TestMain: targetName = 3C6025_SW0E22H
TestMain: W file not exist in /storage/BFD4-1DEB
```

O atualizador **não procura por `update.zip`**. Ele monta um nome-alvo a partir
da versão instalada (`3C6025_SW0E22H`) e varre a **raiz do pendrive** procurando
uma entrada que comece com esse nome. Sem a pasta principal não há o que casar —
ele nem chega a olhar dentro de `OS`. Por isso a pasta não pode ser renomeada: o
nome **é** a chave de busca.

### Formato e tamanho do pendrive não são o problema

Recomenda-se por aí usar pendrive de até 8 GB. Pelos logs, é indiferente: a
unidade montou FAT16 e FAT32, disco inteiro e partição de 2 GB, pendrives de
32 GB e de 8 GB — todos (`MEDIA_MOUNTED`, `/storage/<UUID>`), e escreveu 158 MB
de log em um deles sem falha.

Use FAT32, disco inteiro. Se um pendrive some sozinho da porta USB, o problema é
o hardware dele — troque, mas não por causa do formato.

## Erros na instalação de APKs

**"Success" mas o app não aparece em `pm list packages -3`** — normal para apps
platform-signed: atualizam um pacote de sistema em vez de instalar como app novo.
Não é falha.

**Falha sem mensagem de erro** — a conexão caiu durante a transferência (comum em
APKs grandes por Wi-Fi). Reconecte e **confira antes de reinstalar**, porque o
pacote pode ter entrado assim mesmo:

```bash
adb disconnect 192.168.0.150:5555
adb connect 192.168.0.150:5555
adb shell pm list packages -3
```

**`INSTALL_FAILED_OLDER_SDK`** — o app exige Android novo demais. A unidade é
Android 9 (SDK 28). O Waze 5.21 pede SDK 32 e **não instala**; precisa de versão
antiga (~4.9x). Vale para Spotify, VLC e outros: confira compatibilidade com
Android 9.

**Arquivos `.xapk`** — não são APK, são bundle. Extraia e use `install-multiple`
só com os splits que interessam (`arm64-v8a`; densidade 160 = `mdpi` nesta
unidade):

```bash
unzip app.xapk -d app/
adb install-multiple -r app/base.apk app/config.arm64_v8a.apk \
                        app/config.mdpi.apk app/config.pt.apk
```

## Diagnóstico definitivo: os logs da própria unidade

Se nada acima resolver, isto responde de fato — foi assim que descobrimos o
comportamento do `targetName`.

1. Com o pendrive inserido, toque em **`拷贝IHU日志`** ("copiar logs IHU" —
   1ª linha, 2ª coluna do menu secreto).
2. Espere terminar. A unidade escreve `IHU629G_log_<timestamp>/` no pendrive.
3. No Mac:

```bash
unzip -o recoverylog.zip -d reclog   # last_install vazio = recovery nunca instalou nada
unzip -o mobilelog.zip -d moblog
grep -ah "TestMain" moblog/APLog_*/main_log_* \
  | grep -E "targetName|version suffix|file not exist"
```

As linhas `targetName` e `version suffix` dizem exatamente o que o atualizador
procura. Confie nelas, não na mensagem da tela.

---

# PARTE 3 — Referência e segurança

## O que este patch faz (e o que NÃO faz)

Não é atualização de firmware. É um pacote OTA cujo único trabalho é **habilitar
o ADB na porta TCP 5555**, para instalar APKs pela rede.

O `updater-script` aborta se `ro.product.device != "IHU629G"`. Numa unidade
errada ele **recusa** — não "inutiliza o sistema". O risco real é bem menor do
que a fama sugere, o que não significa que dá pra ser desleixado.

## Referência rápida

| Item | Valor |
|---|---|
| Caminho no pendrive | `<pasta-baixada>/OS/update.zip` na raiz |
| Formato | FAT32, disco inteiro, MBR |
| Senha do menu | `#*` + (mês+10) + dia + hora(12h) |
| Botão de update | `U盘升级` (4ª linha, direita) |
| Botão de Wi-Fi | `无线网络` (última linha, canto inferior direito) |
| Botão de logs | `拷贝IHU日志` (1ª linha, 2ª coluna) |
| Porta ADB | 5555 |
| Sucesso | `Result: added` + `PT ativado` |
| Erro final esperado | `status 7` / `Installation aborted` |
| SDK máximo dos apps | 28 (Android 9) |

## Segurança

O **app de terceiros** mexe em configuração do veículo (ex.: subida automática dos
vidros). A parte de Wi-Fi é inofensiva; as configurações do carro, não. Mude uma
coisa de cada vez e anote o que mudou.

**APKs de fonte duvidosa** — confira quem assinou antes de instalar:

```bash
unzip -p App.apk 'META-INF/*.RSA' | openssl pkcs7 -inform DER -print_certs \
  | openssl x509 -noout -subject -fingerprint -sha256
```

Num lote de APKs que baixei havia um `Games.apk` de 140 MB cujo certificado
forjava a identidade do Google (`O=Google, OU=android, CN=youarefinished`) e que
não constava de nenhuma lista de recomendados. Apagado sem instalar.

**YouTube ReVanced toca vídeo com o carro em movimento** — é literalmente para
isso que ele existe. Decisão sua, mas é risco de verdade ao dirigir, não só
detalhe legal.

---

## 🤝 Referências e Créditos da Comunidade

Este procedimento e as ferramentas de acesso baseiam-se em pesquisas desenvolvidas pela comunidade automotiva e de engenharia reversa:

1. **Jean na Estrada**: Tutorial em vídeo ([YouTube](https://youtu.be/T-77g9hn5LU)) e repositório com os arquivos de desbloqueio OTA (`1111` e `1114`).
2. **Comunidade 4PDA**: Tópico *«Автомобильное ГУ Geely EX2 IHU629G - Обсуждение»* com desmontagens, logs de recuperação e análise do sistema Flyme Auto.
3. **Comunidade XDA Developers**: Descoberta do algoritmo de cálculo da senha dinâmica do menu de engenharia.
4. **XeThongMinh.net**: Tutoriais da comunidade vietnamita sobre desbloqueio do IHU629G e uso do ADB AppControl.

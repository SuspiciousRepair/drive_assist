# ADB root dá acesso total à central do carro

[English](ADB-ROOT-SAFETY.md) · **Português (Brasil)**

> [!WARNING]
> O ADB root é um terminal de administrador na central do veículo. Quem consegue
> acessá-lo pode executar comandos, instalar ou substituir aplicativos, ler dados,
> usar recursos expostos ao Android e alterar arquivos do sistema sem uma
> confirmação na tela do carro.

Esse acesso é útil para instalar e estudar o Drive Assist, mas não é um recurso
comum de consumidor. Trate a central como um computador desbloqueado que viaja
com você e depois se conecta à rede da sua casa.

## O que um invasor poderia descobrir ou alterar

Dependendo dos aplicativos e permissões da central, o acesso pode expor:

- localização atual e anterior, trajetos frequentes e onde o carro fica parado;
- bateria, recarga, velocidade, portas, climatização e outros dados do veículo;
- nomes de redes Wi-Fi, informações da rede, aparelhos pareados e credenciais;
- imagens das câmeras externas e arquivos guardados por aplicativos;
- instalação de software persistente ou funcionamento contínuo capaz de
  descarregar a bateria de 12 V.

Ter root na central multimídia não significa automaticamente controlar direção
ou freios. Mesmo assim, existe um risco sério para a privacidade, a rede da casa,
a confiabilidade da central e as interfaces do veículo.

## Saiba o que o instalador do Drive Assist adiciona

Uma instalação normal por USB possui quatro partes identificáveis:

| Nome | Pacote Android | Para que serve |
| :--- | :--- | :--- |
| Drive Assist | `com.geely.drivemem` | O painel, as configurações, as estatísticas e as integrações opcionais que você usa. |
| ModeHelper | `com.geely.modehelper` | Um pequeno auxiliar privilegiado para operações compatíveis do sistema e do veículo. Ele possui muito mais acesso que um aplicativo comum. |
| Instalador do Drive Assist | `com.geely.installer` | Instala os dois pacotes acima e depois remove a si mesmo. Ele não deve permanecer instalado. |
| Leitor CAN (`roadcastd`) | Não é um pacote Android | Um programa pequeno que o ModeHelper inicia como root pelo ADB local do carro. Ele lê o barramento CAN do carro e nunca envia nada para ele. Fica ativo enquanto **Dados do carro (CAN)** estiver ligado em **Configurações → Sistema**, e essa chave vem ligada. |

A ferramenta opcional de engenharia SysProbe (`com.geely.sysprobe`) não é
necessária para o uso normal e não faz parte da instalação comum. Se você não
instalou deliberadamente uma ferramenta de diagnóstico, ela não deve aparecer.

No Drive Assist, abra **Configurações → Sistema** para consultar a versão instalada
e o SHA-256 do APK em execução. Quem usa ADB também pode listar os pacotes:

```bash
adb shell pm list packages | grep 'com.geely'
```

Leia as notas da versão antes de atualizar. Elas devem informar se a atualização
adiciona outro pacote, pede uma nova permissão, altera um arquivo do sistema ou
cria uma nova conexão de rede. “Instale este APK” não é uma explicação suficiente
do que será colocado no carro.

## Hábitos mais seguros

1. Instale APKs apenas pela versão oficial do projeto ou compile o código-fonte.
   Nome e ícone conhecidos não provam que um arquivo é seguro.
2. Compare o SHA-256 do APK com o valor publicado na versão antes de instalar.
3. Deixe o ADB pela rede desligado quando não estiver usando. O ModeHelper abre
   o ADB por 5 minutos depois que a central liga, mantém aberto enquanto o carro
   está no Wi-Fi que você definiu como de casa e, fora isso, fecha por tempo.
   **Manter o ADB sempre ligado** (Configurações → Sistema) remove esse tempo:
   deixe desligado, a não ser que outro aplicativo precise do ADB. Confirme que
   o ADB está desligado quando você espera que esteja.
4. Coloque o carro em uma rede Wi-Fi de convidados ou IoT isolada. Não libere
   acesso direto a computadores, armazenamento ou outros equipamentos sensíveis.
5. Não publique imagens que mostrem tokens, senhas, endereços de servidores,
   nomes de Wi-Fi, chassi ou localização precisa.
6. Não instale APKs modificados recebidos por mensagens, compartilhamentos de
   arquivos ou fóruns.

O [guia completo de segurança do software](SECURITY-SAFETY.md) explica chaves da
plataforma, permissões privilegiadas, adulteração de APKs, hashes, isolamento da
rede e as descobertas específicas da central IHU629G.

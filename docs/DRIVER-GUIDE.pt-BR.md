# Drive Assist: guia para motoristas

[English](DRIVER-GUIDE.md) · **Português (Brasil)**

O Drive Assist acrescenta à central do Geely EX2 / Geometry E o painel que
faltava: atalhos de climatização, um histórico de verdade de viagens e recargas,
mapas de viagem, informações úteis e conexões opcionais com serviços que você já
usa — tudo em um lugar.

Este guia é sobre usar o app. Protocolos, identificadores do veículo e as
evidências da pesquisa estão na
[biblioteca técnica](README.md#technical-library).

## Seu painel para o dia a dia

![Painel de conforto do Drive Assist](screenshots/comfort-dashboard-live.png)

A tela inicial foi feita para uso rápido enquanto você dirige:

- o número grande é a temperatura externa;
- a escala de conforto pede ar mais frio ou mais quente em um toque, em vez de
  ajustar ventilador, temperatura e A/C um a um;
- botões grandes fazem resfriar, aquecer, recirculação e abrir um vão nos vidros;
- durante a recarga, um cartão de recarga fica no lugar do cartão Estacionado.
  Ele mostra o tempo carregando, o tempo até 100%, o nível da bateria, a energia
  colocada e a potência. Toque no cartão, durante ou depois da recarga, para abrir
  a página da recarga: potência, temperaturas e bateria % na mesma linha do
  tempo (passe o dedo nos gráficos para ler cada valor), os números, o local no
  mapa, AC ou DC e o tempo em 100%. Toque no custo (tem um lápis) para
  informar o preço. Depois da recarga, o cartão fica até você informar o
  preço (zero também); aí ele fecha. O **✕** fecha na hora, e ele também fecha
  quando o carro sai do P. O preço ainda pode ser informado em
  Estatísticas de recarga;
- cartões opcionais mostram o portão da garagem, um painel enviado pelo Home
  Assistant, ou o que o celular toca pelo carro.

Os botões são grandes e a maior parte da tela fica calma, para a informação útil
ser lida de longe.

## O carro lembra das suas preferências

![Modo de condução e regeneração](screenshots/settings-overview-live.png)

O Drive Assist lembra o modo de condução e o nível de regeneração em que o
carro está — seja ajustado na tela do próprio carro, no Drive Assist ou no
botão de ciclo de regeneração — e os reaplica em P quando o carro liga, para
que sobrevivam a cada desligamento. Impulsos temporários (Turbo e a alternância
de regeneração forte) continuam temporários. O silenciamento do AVAS e o AEB
são controlados explicitamente pelo Drive Assist (desligar o AEB na tela do
carro continua temporário; desligá-lo no Drive Assist mantém desligado ao
reiniciar).

Os ajustes ficam em **Config**: Portas, Climatização, Modo de condução, Elementos
(o que aparece na tela inicial), Aparência (temas, dia/noite automático e **Lado
do motorista**: Esquerdo ou Direito, detectado do carro ao iniciar pela primeira
vez e alterável a qualquer momento — levando barras laterais, colunas de
cartões, telas divididas, painel flutuante e arte para o lado do motorista em
carros com volante à direita, mantendo o conteúdo dos cartões da esquerda para
a direita) e Sistema.

Os ajustes do veículo são aplicados pelo pequeno auxiliar **ModeHelper**,
instalado junto com o Drive Assist. O painel e o auxiliar privilegiado ficam
separados, por segurança e confiabilidade.

## Entenda cada dia, não só o hodômetro

![Estatísticas de condução](screenshots/daily-statistics-live.png)

As **Estatísticas de condução** transformam os dados brutos do carro em um
diário:

- distância, tempo dirigindo, velocidade em movimento e número de viagens;
- a bateria em porcentagem: quanto você usou, o nível mínimo e máximo, o que
  as recargas colocaram e a temperatura externa (a da bateria também, com um
  dongle OBD2);
- **Energia por uso**: para onde foi a energia de uma viagem — condução,
  climatização e outros — e quanto a regeneração devolveu (medido pelo CAN);
- eficiência por faixa de velocidade, em Wh/km;
- subida, descida e o saldo de altitude do dia;
- uma linha do tempo de viagens e recargas, com o tempo parado entre elas;
- um **ano de dias**: cada dia colorido pela distância, com visão de semana, mês
  e ano.

Toque em uma viagem terminada (em P) para ver a **rota** em um mapa do
OpenStreetMap, colorida pela velocidade. O mapa precisa de internet na primeira
vez que um lugar aparece; depois fica guardado no carro, então uma viagem que
termina na garagem ainda mostra o mapa. Abaixo da rota está o relevo da viagem, e
os números dela, com o **hodômetro** no início e no fim.

Toque no mapa ao lado das sessões para ver **todas as rotas em uma imagem**,
do dia, semana, mês ou ano que você está vendo. As rotas
são gravadas em **Config → Sistema** (ligado por padrão) e ficam no carro. Dá
para exportar como **GPX** (um arquivo por dia) e a lista como **CSV**, para um
pen drive ou para o celular. Cada viagem, na tela e no CSV, traz o hodômetro no
início e no fim, pronto para um app de despesas.

**Juntar e dividir viagens.** Uma parada curta corta um trajeto em duas
viagens. Para ver o trajeto inteiro como uma só, toque no ícone de **corrente**
no cartão Sessões e depois na primeira e na última viagem. Tudo o que fica entre
elas fica azul: as outras viagens, as paradas e qualquer recarga no caminho.
Toque em **Juntar**. O cartão juntado mostra "2 viagens" (ou mais) e lista as
paradas e recargas dentro dele. Para cortar uma viagem em duas, abra a viagem e
toque em **Dividir**. O primeiro lugar onde você colocou em P já vem
escolhido; **‹** e **›** passam pelos outros (ou arraste no gráfico de altura).
Confira as duas viagens novas à direita e toque em **Dividir aqui**. Uma viagem sem nenhuma parada em P no meio não tem
Dividir. **Desfazer junção** e **Desfazer divisão** na tela da viagem voltam as
viagens como foram gravadas: nada é apagado. O gráfico de altura marca cada
pedágio com uma linha; ao dividir, marca também cada parada em P. Os arquivos CSV e GPX mantêm as
viagens como foram gravadas.

Se o carro foi emprestado em modo **Valet**, toque no cartão dele para uma tela
de detalhe: um mapa de cada trecho, uma linha do tempo, os clipes da dashcam
daquele período e os números (trechos, tempo em movimento e parado, distância,
velocidade e potência máximas, bateria e temperatura).

## Diário do carro: manutenção, custos e incidentes

O **Diário do carro** (o terceiro ícone na tela de estatísticas) guarda o que
você faz no carro e quanto custa:

- **Registros**: pneus, revisão e fluidos, elétrica, estética e carroceria,
  taxas e seguro, acessórios, estacionamento, pedágio e multas. Cada registro
  leva data, hodômetro (preenchido pela data), custo e nota.
- **Próxima manutenção**: cada item com agenda mostra quando vence, por
  distância, por tempo, ou os dois. Os intervalos ficam em **Config →
  Manutenção**: mude com as setas, toque no valor para digitar qualquer número,
  e adicione uma agenda a um item que não tem. Uma linha alterada mostra um
  ícone para voltar ao padrão.
  Informe a **data de retirada** em **Meu carro** (e o hodômetro naquele dia,
  para um usado): a primeira revisão passa a contar os 12 meses a partir dela.
- **Adiado**: quando a oficina olha uma peça que está vencendo e diz para
  esperar, adicione um registro daquele item e escolha **Adiado**, com o
  hodômetro (ou o dia) até quando esperar. O item volta a vencer naquele ponto;
  não conta como trocado.
- **Custo total**: quanto o carro custou no período escolhido, em quatro
  grupos. Toque no cartão para ver em dinheiro, por km ou em porcentagem.
- **Saúde das baterias**: a bateria principal (o estado de saúde precisa de um
  dongle OBD2) e um gráfico de 60 dias da bateria 12 V em repouso. Leituras
  feitas enquanto o carro acorda ou logo após uma recarga aparecem em outra
  cor e não contam contra a bateria.
- **Impactos**: o sensor de movimento do carro anota uma pancada forte durante
  a condução. Em P, o diário pergunta se você quer guardar. Um impacto guardado
  tem o lugar no mapa e, em carro com dashcam, um clipe de 5 segundos. Ajuste a
  sensibilidade, ou desligue, em **Config → Manutenção**.
- **Multas**: digite a hora do auto de infração e o diário acha o lugar na rota
  gravada daquele dia.
- **Exportar**: o diário em CSV, com os clipes, para um pen drive ou para o
  celular.

O diário fica no carro e faz parte do backup.

## Recargas e custo

![Estatísticas de recarga](screenshots/charging-statistics-live.png)

As **Estatísticas de recarga** guardam cada recarga — energia, tempo, variação da
bateria, potência média e o hodômetro ao conectar. Toque em uma recarga (em P) para adicionar ou corrigir
o preço; uma recarga gratuita pode ser salva como zero, e isso continua diferente
de uma recarga sem preço informado. Para trocar o símbolo da moeda (R$ por
padrão), vá em Config → Elementos → Localização. Há visão de mês e ano, um cartão de
temperatura da bateria e um mapa dos lugares onde você recarrega.

Um cartão de **saúde da bateria** mostra os kWh por recarga, as horas que a
bateria passou abaixo de 10% e 20%, e — com um dongle OBD2 — a saúde da bateria.
Sem o dongle, a tela de recarga ainda mostra a temperatura externa durante a
recarga.

## Pedágios e estacionamento

Ligue **Detectar pedágios via OpenStreetMap** em **Config → Integrações →
OpenStreetMap** (desligado de fábrica). O carro passa a marcar cada praça de pedágio e
pórtico Free Flow por onde passa. Ele baixa os pedágios de áreas largas
(cerca de 55 km), nunca a sua rota.

- **Na tela da viagem**, cada pedágio é um pino no mapa e uma marca no
  gráfico de altura. Toque num pedágio para informar o preço. Deixe
  **Lembrar valor para esta praça** ligado, e as próximas viagens por ela
  já levam esse preço.
- **Um pedágio que faltou**: toque no gráfico de altura onde estava o
  pedágio e depois em **+ Pedágio que faltou**. Escolha praça ou Free Flow e
  digite o preço. As próximas viagens no mesmo sentido já o encontram.
- **Estacionamento**: na página de estatísticas, toque numa linha
  **Estacionado** para digitar quanto custou aquela parada. Escolha
  **Estacionamento** ou **Rua** e, se quiser, dê um nome ao local. Na próxima
  vez que parar ali, o nome e os últimos preços já aparecem como botões de um
  toque. Paradas de 15 minutos ou mais mostram um discreto **+ R$**.
  Quando o lugar do carro é conhecido e os mapas do OpenStreetMap estão
  ligados, a janela mostra onde o carro ficou.
- **Filtro Estacionamento**: ao lado de Viagens e Recargas, **Estacionamento**
  mostra só as paradas (de 15 minutos ou mais, ou com preço) e o Manobrista.
  Cada botão de filtro mostra quantos há.
- **No cartão Estacionado** da tela inicial, toque em **+ R$** para digitar o
  preço logo depois de pagar no caixa.
- **Manobrista**: o cartão e a tela do Manobrista têm um **Custo** para
  informar. Ao encerrar o Manobrista em P num lugar onde você já pagou
  manobrista antes, o preço pede só um toque.
- A tela da viagem mostra o **estacionamento na chegada** e os **pedágios +
  estacionamento** da viagem. O **custo de propriedade** do Diário do carro
  conta pedágios e estacionamento no grupo da estrada.

No celular (**Acesso pelo celular**), **Pedágios e estacionamento** lista as
praças e os custos de estacionamento para editar com o teclado do celular, e
baixa os dois em CSV.

## Traga seus serviços

São opcionais. O Drive Assist funciona como painel sem nenhum deles. Configure em
**Config → Integrações**, que mostra o status de cada um (conectado, precisa de
configuração, ou falhando).

### Home Assistant

Conecte seu próprio servidor Home Assistant para ver bateria, autonomia,
localização, recarga, climatização e mais em casa. O Home Assistant também pode
enviar um painel para o carro — a espera de uma balsa, a imagem da campainha — e
fazer aparecer um botão de portão quando o carro chega em casa. Os dados do
veículo vão só para o servidor que você define. Veja o
[guia do Home Assistant](HOME-ASSISTANT.md).

### A Better Routeplanner

O Drive Assist pode enviar bateria e posição ao vivo para o ABRP, para o
planejamento de viagens longas acompanhar o que o carro faz agora. Um dongle OBD2
Bluetooth opcional dá dados de bateria mais finos. Veja o [guia do ABRP](ABRP.md).

### Música

Ligue **Config → Integrações → Música → Música do celular** e a tela inicial
mostra o que o celular toca pelo carro (Bluetooth, Apple CarPlay ou Android
Auto), de qualquer app, com Tocar e Avançar. Não é preciso entrar em nenhuma
conta. O Drive Assist lê isso dos próprios apps de música do carro; nenhuma
permissão é pedida, e nada sai do carro. O pequeno equalizador (três controles deslizantes) no canto de cima,
à direita, do cartão abre os ajustes de som do próprio carro, com o equalizador.
**Cartão grande**, na mesma página, mostra a capa do álbum inteira e botões
grandes de Tocar e Avançar.

Ligue **Busca de letras**, na mesma página, para ver a letra da música. O botão
**Letra** do cartão aumenta o cartão para duas colunas e a altura toda da tela.
No alto ficam a capa, a música, o artista e o álbum. Embaixo vem a letra da música, no tempo dela: a
linha cantada agora em letra grande, a linha anterior e as duas próximas. A
barra embaixo tem Tocar, Avançar e **✕**, que fecha a letra. O cartão lembra a
sua última escolha, aberta ou fechada, de uma música para a outra e depois que
o carro dorme. O cartão volta ao tamanho normal quando o painel do Home Assistant
aparece, ou quando os outros cartões precisam de mais de uma coluna. Toque em
**Letra** de novo e ele cresce de volta: os cartões que ele empurra ficam a um
deslize para o lado. Ele cede de novo quando chegam mais cartões.

A letra vem do [LRCLIB](https://lrclib.net), um site gratuito de letras. Para
achá-la, o carro envia para lá os nomes da música, do artista e do álbum, uma
vez por música. O carro guarda a letra das últimas 1000 músicas e a capa dos
últimos 200 álbuns, então uma música ouvida de novo não precisa de internet.
Capa errada? Segure o dedo nela. O carro esquece a capa desse álbum e pega a
capa do celular de novo. Algumas músicas só têm letra sem tempo no LRCLIB. O cartão
mostra essa letra também, mas ela não anda sozinha, e um pequeno relógio riscado
aparece no alto: toque na metade de baixo da
letra para descer uma linha, na metade de cima para voltar uma. Para uma música
sem letra nenhuma, o botão Letra fica apagado, e tocar nele diz "Letra não
encontrada para esta música". Letra com tempo um pouco adiantada ou atrasada?
Toque no cadeado no alto. O cartão mostra então todas as linhas. Toque em cada
linha quando ela é cantada: as outras linhas acompanham os seus toques, e o
carro guarda o ajuste dessa música. Um toque move todas as linhas igualmente;
mais toques também corrigem uma letra que vai se atrasando. A seta que volta
desfaz o ajuste, e segurar a capa também. O cadeado fecha sozinho na próxima música. Faça isso parado. Com a chave desligada, o carro não envia nada para
lá.

### Dashcam

A dashcam opcional usa as câmeras do próprio carro e pode gravar hora, posição e
velocidade na imagem. Os clipes vão para um pen drive, ou baixam para o celular.
Leia o [guia da dashcam](DASHCAM.md) antes: as regras de armazenamento e de
gravação variam por região.

## Mais coisas que dá para fazer

- **Acesso pelo celular**: leia um QR code na tela do carro para abrir uma página
  no celular com backups, clipes, arquivos de viagem e preços de pedágio e
  estacionamento. Funciona só em P, na rede local.
- **Backup e restauração**: salva seus ajustes, viagens e certificados em um pen
  drive e no carro, com uma senha que você escolhe.
- **Modo mais escuro**: à noite, após um minuto dirigindo sem tocar na tela, a
  tela vai para a luz mínima e uma camada escura cobre o Painel. Um toque ou uma
  troca de marcha traz a luz de volta. Funciona só no próprio horário (21:00 às
  06:00 por padrão; arraste as alças na linha de 24 horas). Escuridão deixa
  ainda mais escuro: 0 % é só a luz mínima, 100 % desliga a tela (70 % por
  padrão). Ligue **Em todas as telas** para escurecer todos os apps, não só o
  Painel. O botão Experimentar mostra na hora.
- **Tradução dos apps do carro** (Config → Integrações): texto melhor nos apps
  Energia, Wi-Fi, Configurações e Permissões do próprio carro. Os arquivos do
  carro não mudam; Remover ou a Limpeza tira. Instalar e Remover esperam o carro em P.
- **Som de travamento**: troca o som que o carro faz ao travar, ou importa o seu.
- **Limpeza**: uma tela que remove o Drive Assist, o auxiliar e o leitor CAN, e
  devolve o som de travamento do próprio carro — útil antes de uma visita à
  oficina.

## O que o Drive Assist controla

O Drive Assist atua sobretudo em conforto e conveniência: climatização, modo de
condução, regeneração, luz interna, vidros, recarga e o portão opcional do Home
Assistant.

O aplicativo não dirige, freia, acelera, destranca portas nem abre o porta-malas.
Os sinais de portas e carroceria exibidos são apenas de leitura.

Duas chaves afetam sistemas de segurança, e as duas são escolha sua: uma pode
desligar o AEB (frenagem automática de emergência) e outra pode silenciar o AVAS
(o som de aviso para pedestres). O [guia de segurança e privacidade](WELCOME.md)
lista tudo o que o aplicativo pode ler, mudar e enviar.

## Como instalar no carro

O jeito mais fácil para a maioria é o instalador único por USB — veja o
[guia rápido de instalação](QUICK-INSTALL.md). O carro já precisa estar liberado
para instalar aplicativos de terceiros.

Quem já usa ADB tem métodos pelo computador também. Comece pelo guia de escolha em
[Instalação](INSTALL-GUIDE.md).

## Para onde ir agora

- [Instalar o Drive Assist](QUICK-INSTALL.md)
- [Entender o acesso ADB root](ADB-ROOT-SAFETY.md)
- [Conectar o Home Assistant](HOME-ASSISTANT.md)
- [Conectar o ABRP](ABRP.md)
- [Ler a explicação de segurança e privacidade](WELCOME.md)
- [Ver as descobertas técnicas](README.md#technical-library)

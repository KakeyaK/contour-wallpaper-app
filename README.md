# Contour Live Wallpaper

Live wallpaper para Android que desenha curvas de nível sobre um gradiente cujas cores
variam continuamente ao longo do dia. Implementação da spec `Contour Live Wallpaper`.

## Gerar o APK sem Android Studio

1. Crie um repositório no GitHub e envie esta pasta inteira (inclusive `.github/`).
2. Cada push dispara o workflow **Build debug APK**. Abra a aba *Actions*, entre na
   execução e baixe o artefato `contour-wallpaper-debug` (`app-debug.apk`).
3. Instale no aparelho (permitir fontes desconhecidas) e aplique pelo seletor de papéis
   de parede ou pelo botão **Aplicar papel de parede** dentro do app.

Localmente com Android SDK: `./gradlew assembleDebug` (JDK 17).

### Build sem Android SDK (só pacotes do Ubuntu)

`build-local.sh` compila o mesmo código sem Gradle, usando `aapt2`, `dx`, `apksigner` e
`zipalign` dos repositórios do Ubuntu, o `android.jar` da API 23 e o `kotlinc` do GitHub:

```
sudo apt install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk-headless
# kotlinc 2.1: https://github.com/JetBrains/kotlin/releases -> descompactar em /opt/kotlinc
./build-local.sh          # -> build-local/contour-debug.apk
```

Por isso o código usa só APIs disponíveis na API 23 (e a UI de configurações é feita com
Views, sem Compose nem AndroidX).

## Assets

Os PNGs em `app/src/main/assets/` são gerados por `tools/gerar_assets.py`
(seed 42, 34 curvas, 5 oitavas):

```
pip install numpy matplotlib
cd tools && python gerar_assets.py     # -> tools/assets/*.png
cp tools/assets/*.png ../app/src/main/assets/
```

Mudar `SEED` no topo do script gera outro relevo. Depois é preciso recompilar o APK —
as curvas não são geradas no aparelho (está fora do escopo da spec).

**Correção aplicada ao script:** os níveis de altitude passaram a ser calculados uma
única vez, no campo mestre, e reusados nos dois conjuntos (`contour_levels`). Antes,
cada recorte calculava os seus a partir do próprio min/max — como a faixa central tem
min/max diferentes do campo inteiro, as curvas da tela externa caíam em altitudes
diferentes das da interna e o mapa saltava ao desdobrar (critério de aceitação 6).

## Estrutura

| Arquivo | Papel |
|---|---|
| `ColorAnchor.kt` | Modelo da âncora (hora + 5 cores), `DayColors`, `LayerMode`, JSON |
| `ColorMath.kt` | Hex, lerp, smoothstep, luminância WCAG, HLS, **trava de contraste** |
| `SolarTime.kt` | Nascer/pôr do sol (NOAA) e o `SolarWarp` que ajusta a hora da paleta |
| `SimplePalette.kt` | Modo de uma cor só: deriva as cinco cores de uma âncora a partir de um seed |
| `Palette.kt` | Paleta padrão (6 âncoras), interpolação com volta pela meia-noite |
| `PaletteRepository.kt` | Persistência em `SharedPreferences`, importar/exportar JSON |
| `ContourRenderer.kt` | Compositor: gradiente + PNGs tingidos com `SRC_IN`; escolha `_cover`/`_main` |
| `ContourWallpaperService.kt` | `WallpaperService`: redesenho por minuto, parada total quando invisível |
| `ui/SettingsActivity.kt` | Tela de configurações: prévia com slider de tempo, âncoras, opções, import/export |
| `ui/AnchorEditorActivity.kt` | Editor de âncora: hora, nome, 5 cores, excluir |
| `ui/ColorPicker.kt` | Seletor HSV + hex (diálogo) |
| `ui/PreviewView.kt` | Prévia usando o mesmo compositor do serviço |
| `build-local.sh` | Build sem Android SDK (ver acima) |

## Interface

A interface do app é toda em inglês (incluindo os nomes das âncoras padrão: Dawn,
Morning, Midday, Afternoon, Dusk, Night). Os comentários do código e este README seguem
em português.

## Modo de uma cor só

No editor de âncora há duas opções: **One colour** e **All five**. No primeiro, o usuário
escolhe uma cor base e as cinco saem dela por uma regra fixa (`SimplePalette.derive`):

- o fundo vira duas versões da cor base, mais escura em cima e mais clara embaixo, com um
  leve desvio de matiz e menos saturação embaixo (o que o céu faz de verdade); se a cor
  base estiver muito perto do preto ou do branco, o par inteiro desliza para dentro da
  faixa, senão o gradiente achata;
- as linhas vão para o lado claro se o fundo for escuro e para o escuro nos demais casos;
- cada faixa de altitude tem um **contraste alvo**: 2.3, 3.1 e 4.2. Esse é o ponto que
  resolve o problema das linhas brigando com o fundo — antes só existia um piso (a trava
  de legibilidade), então nada impedia uma linha de estourar para 8 ou 10 de contraste;
- os matizes das linhas ficam a poucos graus da cor base (-12°, +6°, +18°), com a do meio
  dessaturada, para as três faixas se separarem sem sair da família de cor.

O alvo nunca fica abaixo da trava de legibilidade escolhida nas configurações, então a
trava não precisa corrigir nada depois. O editor mostra as cinco cores geradas e o
contraste de cada linha, para a regra não ser uma caixa-preta.

O seed fica gravado na âncora (campo `seed` no JSON) só para a tela lembrar de onde as
cores vieram — as cinco cores continuam sempre gravadas, e o desenho e a interpolação não
mudam nada. Editar qualquer uma das cinco à mão desliga o modo simples daquela âncora.
As âncoras padrão vêm sem seed, no modo **All five**, porque suas cores são escolhidas a
mão e não seguem a regra.

## Modo solar (acompanhar o nascer e o pôr do sol)

Ligado em **Configurações → Nascer e pôr do sol**. A hora do relógio passa por um mapa
contínuo antes de consultar a paleta:

- o nascer do sol real cai exatamente na âncora das **5:00**;
- o pôr do sol real cai exatamente na âncora das **20:00**;
- o dia e a noite reais são esticados ou comprimidos sobre esses dois trechos.

No inverno o miolo claro da paleta encolhe e a noite se alonga. O mapa é contínuo e
monotônico, inclusive na virada da meia-noite, então não há saltos de cor.

Enquanto o modo está ligado, ele **sobrescreve os horários definidos pelo usuário**, e a
tela deixa isso explícito em três lugares: o aviso no topo da seção, cada âncora da lista
mostrando `05:00 → 06:14 today`, e uma nota dentro do editor da âncora. A prévia também
mostra as duas horas (`relógio → paleta`) quando o modo está agindo.

O cálculo é o algoritmo solar do NOAA, só aritmética: nada de rede, nada de GPS em tempo
de desenho. A tela de configurações lê a localização **uma única vez** (botão *Usar minha
localização*, permissão `ACCESS_COARSE_LOCATION`, opcional) e guarda latitude e longitude;
o `WallpaperService` apenas faz a conta a cada redesenho, sem custo de bateria. Quem
preferir pode digitar as coordenadas e nunca conceder a permissão.

Em dias sem nascer ou pôr do sol (sol da meia-noite, noite polar) e sem coordenadas
definidas, o app volta sozinho ao relógio normal.

As coordenadas e o modo solar não entram no JSON da paleta (são ajustes de local, não de
cor) e o *Restaurar padrão* não mexe neles.

## Ajustes na paleta padrão

As âncoras de 17h e 20h saíram da tabela original da spec porque o topo do gradiente
ficava berrante ocupando a tela inteira:

| Hora | Campo | Antes | Depois |
|---|---|---|---|
| 17.0 | bgTop | `#F6D8A8` | `#C2B2AE` |
| 17.0 | bgBottom | `#E79A63` | `#DE9468` |
| 17.0 | line2 | `#C56B14` | `#A95C11` |
| 20.0 | bgTop | `#432A5C` | `#2E2140` |
| 20.0 | bgBottom | `#B5567A` | `#A85670` |
| 20.0 | line3 | `#2B1930` | `#190F1C` |

O topo das 17h virou um cinza-quente frio, que é o que o céu faz de verdade no fim da
tarde (frio em cima, quente no horizonte), e o das 20h ficou um violeta mais fundo. As
duas cores de linha mudaram junto porque, com os fundos novos, caíam abaixo de 2.1 de
contraste — os valores da tabela já são os que a trava produziria, mantendo a regra de
que a paleta padrão sai de fábrica travada.

Isso altera o critério de aceitação 5: *Restaurar padrão* devolve estas seis âncoras, não
as da tabela original.

## Decisões que a spec deixou em aberto

- **Cor única** usa a cor da *linha 2* (intermediária) sobre `linhas_*.png`.
- Escala: encaixa pela altura e centraliza horizontalmente (como pedido). Se a superfície
  de parallax do launcher for mais larga que o bitmap escalado, a escala sobe para
  não deixar bordas vazias.
- Nova âncora nasce com a hora atual e as cores interpoladas desse instante.
- No modo solar as referências são 5:00 e 20:00, as horas do "Amanhecer" e do "Anoitecer"
  da paleta padrão. Editar as cores dessas âncoras funciona normalmente; mover as horas
  delas muda o alinhamento com o sol.
- Formato do JSON exportado: `{"version":1,"anchors":[...],"layerMode":"three","contrastTarget":2.1,"intervalMinutes":1}`.
  A importação também aceita só a lista de âncoras.

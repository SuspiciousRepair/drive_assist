# car-stubs — stubs de compilação do `android.car`

Substituem o `android.car.jar` extraído do firmware do carro
(`/system/framework/android.car.jar`).

## Por quê

O jar real é do firmware da Geely: não é redistribuível, e exigir que cada pessoa
extraia o dela obriga a **ter um carro para compilar o projeto**. Como toda a
superfície usada são métodos AOSP públicos e estáveis, um stub escrito à mão
resolve — o compilador só precisa das assinaturas; em runtime o Android carrega a
implementação real do dispositivo.

## O que cobre

4 tipos, 12 métodos:

| tipo | métodos |
|---|---|
| `android.car.Car` | `createCar` (2 sobrecargas), `connect`, `disconnect`, `isConnected`, `getCarManager`, `PROPERTY_SERVICE` |
| `CarPropertyManager` | `get/set{Int,Float,Boolean}Property`, `getProperty` (2), `getPropertyList` |
| `CarPropertyValue` | `getValue`, `getStatus`, `STATUS_*` |
| `CarPropertyConfig` | `getAreaIds`, `getMinValue/getMaxValue` (por área), `getPropertyType` |

**Nada proprietário da Geely está aqui, nem poderia estar.** Os functionIds
(605028608 etc.) são apenas os inteiros do parâmetro `propId`; a camada de
adaptação que os traduz vive no `car_service` do carro.

## Verificado

Assinaturas conferidas com `javap` contra o `android.car.jar` de um Geely IHU629G
(Android 9). O APK gerado com estes stubs tem **exatamente o mesmo tamanho**
(592.280 bytes) do gerado com o jar do firmware — como esperado, já que stubs só
existem em tempo de compilação.

## Uso

```sh
javac -source 8 -target 8 -classpath "$ANDROID_JAR" -d out $(find . -name '*.java')
jar cf car-stubs.jar -C out .
CARJAR=car-stubs.jar ./build.sh
```

Os corpos lançam `RuntimeException("stub")` de propósito: se algum executar, é
sinal de que o jar vazou para dentro do APK em vez de ficar só no classpath de
compilação.

Licença: Apache-2.0, como o AOSP.

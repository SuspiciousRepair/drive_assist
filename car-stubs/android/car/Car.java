package android.car;

import android.content.Context;
import android.content.ServiceConnection;

/**
 * Stub de compilacao do android.car (AOSP Automotive).
 *
 * POR QUE ISTO EXISTE: o `android.car.jar` real vive no firmware do carro
 * (/system/framework/android.car.jar). Extrair e redistribuir esse arquivo nao
 * e legal nem pratico — obrigaria cada pessoa a ter um carro para compilar o
 * projeto. Como toda a superficie usada sao metodos AOSP publicos e estaveis,
 * um stub escrito a mao resolve: o compilador so precisa das assinaturas; em
 * runtime o Android carrega a implementacao real do dispositivo.
 *
 * Os corpos lancam de proposito — se algum deles executar, e sinal de que o jar
 * do stub vazou para o APK em vez de ficar so no classpath de compilacao.
 *
 * IMPORTANTE: os functionIds da Geely (605028608 etc.) NAO estao aqui e nem
 * poderiam estar. Sao inteiros passados a estes metodos padrao; a traducao
 * acontece no car_service do carro, nao nesta API.
 *
 * Assinaturas conferidas contra o android.car.jar de um Geely IHU629G
 * (Android 9) via javap. Licenca: Apache-2.0, como o AOSP.
 */
public final class Car {
    public static final String PROPERTY_SERVICE = "property";

    private Car() { throw new RuntimeException("stub"); }

    public static Car createCar(Context context) { throw new RuntimeException("stub"); }

    public static Car createCar(Context context, ServiceConnection serviceConnection) {
        throw new RuntimeException("stub");
    }

    public void connect() throws IllegalStateException { throw new RuntimeException("stub"); }

    public void disconnect() { throw new RuntimeException("stub"); }

    public boolean isConnected() { throw new RuntimeException("stub"); }

    public Object getCarManager(String serviceName) { throw new RuntimeException("stub"); }
}

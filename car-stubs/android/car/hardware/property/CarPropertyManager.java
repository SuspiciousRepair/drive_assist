package android.car.hardware.property;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;

import java.util.List;

/**
 * Stub de compilacao — ver android.car.Car para o porque.
 *
 * Esta e a API que o app inteiro usa para falar com o VHAL. Os functionIds da
 * Geely sao apenas os inteiros do parametro `propId`: a camada de adaptacao que
 * os traduz para propriedades reais vive no car_service do carro, nao aqui.
 * Por isso um stub generico basta — nao ha nada proprietario nesta interface.
 *
 * Assinaturas conferidas contra o android.car.jar de um Geely IHU629G via javap.
 */
public class CarPropertyManager {

    protected CarPropertyManager() { throw new RuntimeException("stub"); }

    public boolean getBooleanProperty(int propId, int area) { throw new RuntimeException("stub"); }

    public float getFloatProperty(int propId, int area) { throw new RuntimeException("stub"); }

    public int getIntProperty(int propId, int area) { throw new RuntimeException("stub"); }

    public <E> CarPropertyValue<E> getProperty(int propId, int area) { throw new RuntimeException("stub"); }

    public <E> CarPropertyValue<E> getProperty(Class<E> clazz, int propId, int area) {
        throw new RuntimeException("stub");
    }

    /**
     * Metadata declarada pelo VHAL: areaIds validos e min/max por area.
     * E a base da descoberta assistida — sem isto so resta adivinhar areas.
     */
    public List<CarPropertyConfig> getPropertyList() { throw new RuntimeException("stub"); }

    public void setBooleanProperty(int propId, int area, boolean val) { throw new RuntimeException("stub"); }

    public void setFloatProperty(int propId, int area, float val) { throw new RuntimeException("stub"); }

    public void setIntProperty(int propId, int area, int val) { throw new RuntimeException("stub"); }

    /**
     * Eventos, em vez de polling. O carro avisa quando a propriedade muda — e a
     * taxa ONCHANGE e a unica que faz sentido para porta, vidro e afins, que nao
     * tem "amostragem": ou mudaram ou nao.
     *
     * Assinaturas conferidas contra o android.car.jar do IHU629G via javap, como
     * o resto deste arquivo.
     */
    public static final float SENSOR_RATE_ONCHANGE = 0f;
    public static final float SENSOR_RATE_NORMAL   = 1f;

    public interface CarPropertyEventCallback {
        void onChangeEvent(CarPropertyValue value);
        void onErrorEvent(int propId, int areaId);
    }

    public boolean registerCallback(CarPropertyEventCallback cb, int propId, float rate) {
        throw new RuntimeException("stub");
    }

    public void unregisterCallback(CarPropertyEventCallback cb) { throw new RuntimeException("stub"); }

    public void unregisterCallback(CarPropertyEventCallback cb, int propId) {
        throw new RuntimeException("stub");
    }
}

package android.car.hardware;

import java.util.List;

/**
 * Stub de compilacao — ver android.car.Car para o porque.
 *
 * Descreve uma propriedade: quais areas existem e qual a faixa valida. E o que
 * permite parar de chutar areas ({0,1,16777216}, {75,0,1}...) e de hardcodar
 * limites (5..32 A) — o carro declara ambos.
 */
public class CarPropertyConfig<T> {
    protected CarPropertyConfig() { throw new RuntimeException("stub"); }

    public int getPropertyId() { throw new RuntimeException("stub"); }

    public int[] getAreaIds() { throw new RuntimeException("stub"); }

    public int getAreaType() { throw new RuntimeException("stub"); }

    public Class<T> getPropertyType() { throw new RuntimeException("stub"); }

    public T getMinValue() { throw new RuntimeException("stub"); }

    public T getMaxValue() { throw new RuntimeException("stub"); }

    public T getMinValue(int areaId) { throw new RuntimeException("stub"); }

    public T getMaxValue(int areaId) { throw new RuntimeException("stub"); }

    public List<Integer> getConfigArray() { throw new RuntimeException("stub"); }
}

package android.car.hardware;

/**
 * Stub de compilacao — ver android.car.Car para o porque.
 *
 * Valor lido do VHAL com o status da leitura. O status importa: uma propriedade
 * pode existir e responder UNAVAILABLE (ex.: dado que so faz sentido com o carro
 * ligado), e tratar isso como valor valido produz leitura falsa.
 */
public class CarPropertyValue<T> {
    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_UNAVAILABLE = 1;
    public static final int STATUS_ERROR = 2;

    protected CarPropertyValue() { throw new RuntimeException("stub"); }

    public int getStatus() { throw new RuntimeException("stub"); }

    public T getValue() { throw new RuntimeException("stub"); }

    public int getPropertyId() { throw new RuntimeException("stub"); }

    public int getAreaId() { throw new RuntimeException("stub"); }
}

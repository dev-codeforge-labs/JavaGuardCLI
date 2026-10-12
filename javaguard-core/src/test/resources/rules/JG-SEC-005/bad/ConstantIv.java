import javax.crypto.spec.IvParameterSpec;

class ConstantIv {
    IvParameterSpec iv() {
        return new IvParameterSpec(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
    }
}

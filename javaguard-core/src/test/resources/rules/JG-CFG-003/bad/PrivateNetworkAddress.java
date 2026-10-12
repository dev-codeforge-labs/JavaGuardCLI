class PrivateNetworkAddress {
    String inventory() {
        return call("http://192.168.1.40:9000/inventory");
    }

    String call(String url) {
        return url;
    }
}

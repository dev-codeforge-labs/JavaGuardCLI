class NoTransactionAtAll {
    private CustomerRepository repository;

    public Customer save(Customer customer) {
        return repository.save(customer);
    }
}

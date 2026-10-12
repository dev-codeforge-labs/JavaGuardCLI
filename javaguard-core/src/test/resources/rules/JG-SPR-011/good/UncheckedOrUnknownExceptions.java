import org.springframework.transaction.annotation.Transactional;

class NotFoundException extends RuntimeException {
}

class TimeoutException extends RuntimeException {
}

@Transactional
class UncheckedOrUnknownExceptions {
    public void load() throws NotFoundException, IllegalStateException {
    }

    public void call() throws RemoteServiceException {
    }

    public void shadowed() throws TimeoutException {
    }
}

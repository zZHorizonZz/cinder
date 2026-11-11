package dev.horizon.cinder;

import java.time.Instant;
import java.util.Optional;

/**
 * Where an operation runs: its database, its model, what it does and when. A part reads it to know the fields of the
 * statement, and to run more operations.
 *
 * @param database the database. Its operations run in the transaction of the operation, if it has one.
 * @param model the model
 * @param transaction the transaction that the operation runs in, if it runs in one
 * @param kind what the operation does
 * @param now the time of the operation, the same for each of its parts, so that the times that it writes agree
 * @param <E> the type of the entities
 */
public record Context<E>(Database database, Model<E> model, Optional<Transaction> transaction, Invocation.Kind kind,
    Instant now) {
}

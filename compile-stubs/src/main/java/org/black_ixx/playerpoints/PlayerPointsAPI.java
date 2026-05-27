package org.black_ixx.playerpoints;

import java.util.UUID;

public interface PlayerPointsAPI {
    int look(UUID playerId);

    boolean take(UUID playerId, int amount);

    boolean give(UUID playerId, int amount);
}

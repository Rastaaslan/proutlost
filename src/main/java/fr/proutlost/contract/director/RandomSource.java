package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Pure indexed draw: calling twice does not advance anything. Caller must durably audit seed/index before use. */
public interface RandomSource {
    RandomDraw draw(Digest seed, long drawIndex, long bound);
}

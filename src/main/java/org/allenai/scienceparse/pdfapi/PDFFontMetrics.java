package org.allenai.scienceparse.pdfapi;

import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class PDFFontMetrics {
    public final String name;
    public final float ptSize;
}

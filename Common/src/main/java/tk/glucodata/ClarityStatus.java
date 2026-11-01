package tk.glucodata;

import androidx.annotation.Keep;

@Keep
public final class ClarityStatus {
    public boolean available, enabled, libre3History, numbers;
    public long since, lastSuccess, acknowledgedCount, acknowledgedLast;
    public long pendingCount, pendingFirst, pendingLast;
    public String account = "", status = "";
}

package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GuiSettings implements Serializable {

  @SerializedName("enabled")
  public Boolean isEnabled = true;
  @SerializedName("running")
  public transient Boolean isRunning = false;
  @SerializedName("input_directory")
  public String inputDirectory = "";
  @SerializedName("recent_input_files")
  public List<String> recentInputFiles = new ArrayList<>();
  @SerializedName("dialog_confirmation_timeout")
  public int dialogConfirmationTimeout = 5;
  public transient boolean exitWhenFinished;
}
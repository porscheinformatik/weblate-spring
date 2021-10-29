package at.porscheinformatik.weblate.spring;

import java.io.Serializable;
import java.util.List;
import java.util.StringJoiner;

import com.fasterxml.jackson.annotation.JsonProperty;

public class WeblateUnit implements Serializable
{
  public static final int serialVersionUID = 1;

  private String translation;
  private List<String> source;
  @JsonProperty(value = "previous_source")
  private String previousSource;
  private List<String> target;
  @JsonProperty(value = "id_hash")
  private String idHash;
  @JsonProperty(value = "content_hash")
  private String contentHash;
  private String location;
  /**
   * translation key
   */
  private String context;
  private String note;
  private String flags;
  /**
   * unit state, 0 - not translated, 10 - needs editing, 20 - translated, 30 - approved, 100 - read only
   */
  private Integer state;
  private Boolean fuzzy;
  private Boolean translated;
  private Boolean approved;
  private Integer position;
  @JsonProperty(value = "has_suggestion")
  private Boolean hasSuggestion;
  @JsonProperty(value = "has_comment")
  private Boolean hasComment;
  @JsonProperty(value = "has_failing_check")
  private Boolean hasFailingCheck;
  @JsonProperty(value = "num_words")
  private Integer numWords;
  private Integer priority;
  private Integer id;
  private String explanation;
  @JsonProperty(value = "extra_flags")
  private String extraFlags;
  @JsonProperty(value = "web_url")
  private String webUrl;
  @JsonProperty(value = "source_unit")
  private String sourceUnit;

  public String getTranslation()
  {
    return translation;
  }

  public void setTranslation(String translation)
  {
    this.translation = translation;
  }

  public List<String> getSource()
  {
    return source;
  }

  public void setSource(List<String> source)
  {
    this.source = source;
  }

  public String getPreviousSource()
  {
    return previousSource;
  }

  public void setPreviousSource(String previousSource)
  {
    this.previousSource = previousSource;
  }

  public List<String> getTarget()
  {
    return target;
  }

  public void setTarget(List<String> target)
  {
    this.target = target;
  }

  public String getIdHash()
  {
    return idHash;
  }

  public void setIdHash(String idHash)
  {
    this.idHash = idHash;
  }

  public String getContentHash()
  {
    return contentHash;
  }

  public void setContentHash(String contentHash)
  {
    this.contentHash = contentHash;
  }

  public String getLocation()
  {
    return location;
  }

  public void setLocation(String location)
  {
    this.location = location;
  }

  public String getContext()
  {
    return context;
  }

  public void setContext(String context)
  {
    this.context = context;
  }

  public String getNote()
  {
    return note;
  }

  public void setNote(String note)
  {
    this.note = note;
  }

  public String getFlags()
  {
    return flags;
  }

  public void setFlags(String flags)
  {
    this.flags = flags;
  }

  public Integer getState()
  {
    return state;
  }

  public void setState(Integer state)
  {
    this.state = state;
  }

  public Boolean getFuzzy()
  {
    return fuzzy;
  }

  public void setFuzzy(Boolean fuzzy)
  {
    this.fuzzy = fuzzy;
  }

  public Boolean getTranslated()
  {
    return translated;
  }

  public void setTranslated(Boolean translated)
  {
    this.translated = translated;
  }

  public Boolean getApproved()
  {
    return approved;
  }

  public void setApproved(Boolean approved)
  {
    this.approved = approved;
  }

  public Integer getPosition()
  {
    return position;
  }

  public void setPosition(Integer position)
  {
    this.position = position;
  }

  public Boolean getHasSuggestion()
  {
    return hasSuggestion;
  }

  public void setHasSuggestion(Boolean hasSuggestion)
  {
    this.hasSuggestion = hasSuggestion;
  }

  public Boolean getHasComment()
  {
    return hasComment;
  }

  public void setHasComment(Boolean hasComment)
  {
    this.hasComment = hasComment;
  }

  public Boolean getHasFailingCheck()
  {
    return hasFailingCheck;
  }

  public void setHasFailingCheck(Boolean hasFailingCheck)
  {
    this.hasFailingCheck = hasFailingCheck;
  }

  public Integer getNumWords()
  {
    return numWords;
  }

  public void setNumWords(Integer numWords)
  {
    this.numWords = numWords;
  }

  public Integer getPriority()
  {
    return priority;
  }

  public void setPriority(Integer priority)
  {
    this.priority = priority;
  }

  public Integer getId()
  {
    return id;
  }

  public void setId(Integer id)
  {
    this.id = id;
  }

  public String getExplanation()
  {
    return explanation;
  }

  public void setExplanation(String explanation)
  {
    this.explanation = explanation;
  }

  public String getExtraFlags()
  {
    return extraFlags;
  }

  public void setExtraFlags(String extraFlags)
  {
    this.extraFlags = extraFlags;
  }

  public String getWebUrl()
  {
    return webUrl;
  }

  public void setWebUrl(String webUrl)
  {
    this.webUrl = webUrl;
  }

  public String getSourceUnit()
  {
    return sourceUnit;
  }

  public void setSourceUnit(String sourceUnit)
  {
    this.sourceUnit = sourceUnit;
  }
}

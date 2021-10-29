package at.porscheinformatik.weblate.spring;

import java.io.Serializable;
import java.util.List;

public class WeblateUnits implements Serializable
{
  public static final int serialVersionUID = 1;

  private Long count;
  private List<WeblateUnit> results;

  public Long getCount()
  {
    return count;
  }

  public void setCount(Long count)
  {
    this.count = count;
  }

  public List<WeblateUnit> getResults()
  {
    return results;
  }

  public void setResults(List<WeblateUnit> results)
  {
    this.results = results;
  }

}

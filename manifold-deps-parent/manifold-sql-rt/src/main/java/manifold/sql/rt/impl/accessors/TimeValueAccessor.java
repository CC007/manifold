/*
 * Copyright (c) 2023 - Manifold Systems LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package manifold.sql.rt.impl.accessors;

import manifold.sql.rt.api.BaseElement;
import manifold.sql.rt.api.ValueAccessor;

import java.sql.*;
import java.time.LocalTime;

public class TimeValueAccessor implements ValueAccessor
{
  @Override
  public int getJdbcType()
  {
    return Types.TIME;
  }

  @Override
  public Class<?> getJavaType( BaseElement elem )
  {
    return LocalTime.class;
  }

  @Override
  public LocalTime getRowValue( ResultSet rs, BaseElement elem ) throws SQLException
  {
    Time time = rs.getTime( elem.getPosition() );
    return time == null ? null : time.toLocalTime();
  }

  @Override
  public void setParameter( PreparedStatement ps, int pos, Object value ) throws SQLException
  {
    if( value == null )
    {
      ps.setNull( pos, getJdbcType() );
    }
    else if( value instanceof LocalTime )
    {
      ps.setTime( pos, Time.valueOf( (LocalTime)value ) );
    }
    else
    {
      ps.setObject( pos, value, getJdbcType() );
    }
  }
}

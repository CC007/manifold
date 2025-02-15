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

package manifold.sql.rt.api;

import manifold.rt.api.Bindings;

import java.sql.Connection;
import java.util.Properties;

/**
 * Configuration for connecting to a JDBC datasource. Maps directly to JSON data obtained from a {@code .dbconfig} file,
 * or at runtime if the config is provided from a {@link DbConfigProvider} SPI implementation, it is obtained programmatically.
 * At compile-time the dbconfig is supplied as a resource file.
 */
public interface DbConfig
{
  String DEFAULT_SCHEMA_PKG = "sql.schema.types";

  /**
   * (Provided) Name corresponding with the dbconfig file: MyDatabase.dbconfig -> "MyDatabase". This name will be the parent
   * Java type name of db table entities.
   * <p/>
   * If the dbconfig does not provide a "schemaName" and the JDBC URL does not indicate a schema, this name is also the
   * schema name.
   * <p/>
   * This name is automatically set by manifold on load of the file, or if this DbConfig was constructed via {@link DbConfigProvider}
   * SPI, it will be supplied by the implementation.
   */
  String getName();

  /**
   * (Optional) The catalog containing the schema. If dbconfig does not provide a "catalogName", all accessible database
   * catalogs are searched for a schema matching "schemaName", in no particular order. If "catalogName" is the empty string
   * {@code ""}, this indicates only schemas without a catalog will be considered.
   * <p/>
   * Note, it is common for JDBC drivers to support naming the catalog in the URL. In this case the catalog name is unnecessary
   * in the dbconfig.
   */
  String getCatalogName();

  /**
   * (Optional) The name of the database schema used for this configuration. If no "schemaName" is provided, the dbconfig
   * file name is used as the schema name. If the file name, does not correspond with a schema in the database, a default
   * schema may be chosen.
   * <p/>
   * Note, it is common for JDBC drivers to support naming the schema in the URL. In this case the schema name is unnecessary
   * in the dbconfig.
   */
  String getSchemaName();

  /**
   * (Provided: Compile) Location of dbconfig file corresponding with this class. This field is supplied and is relevant
   * only at compile-time. This is fields is used for logging and debugging.
   */
  String getPath();

  /**
   * (Required: Compile/Run) JDBC URL for database (run time). If {@link #getBuildUrl()} returns null, this URL is used
   * both for runtime and build time.
   */
  String getUrl();

  /** (Optional) JDBC URL for database (build time). This database may be empty, it is used solely for metadata during compilation. */
  String getBuildUrl();

  /**
   * (Optional) The fully qualified package name where schema .class files will be generated.
   * If not provided, the default package will be used: {@link #DEFAULT_SCHEMA_PKG}.
   * <p/>
   * This property is used exclusively for compile time.
   */
  String getSchemaPackage();

  /** (Optional) Username for database account */
  String getUser();
  /** (Optional) Password for database account */
  String getPassword();

  /**
   * (Optional) If true, this dbconfig is applied to SQL resources that do not specify a dbconfig name.
   * <p/>
   * To specify a dbconfig
   * name a .sql file follows the naming convention:<br>
   * {@code MyQuery.dbconfigName.sql}<br>
   * <p/>
   * Embedded SQL follows a similar pattern:<br>
   * {@code "[.sql:dbconfigName/] select * from ..."}
   */
  boolean isDefault();

  /** (Optional) JDBC connection properties in JSON format. These properties are driver-specific. See {@link #toProperties()}. */
  Bindings getProperties();

  /** Returns the build URL if provided, otherwise the runtime URL */
  default String getBuildUrlOtherwiseRuntimeUrl()
  {
    String buildUrl = getBuildUrl();
    return buildUrl == null || buildUrl.isEmpty() ? getUrl() : buildUrl;
  }

  /** Convert the connection related config attributes to a Properties object */
  default Properties toProperties()
  {
    Properties props = new Properties();
    Bindings properties = getProperties();
    if( properties != null )
    {
      props.putAll( properties );
    }

    // override direct user, password if provided

    String user = getUser();
    if( user != null )
    {
      props.put( "user", user );
    }

    String password = getPassword();
    if( password != null )
    {
      props.put( "password", password );
    }

    return props;
  }

  void init( Connection connection, String url );
}

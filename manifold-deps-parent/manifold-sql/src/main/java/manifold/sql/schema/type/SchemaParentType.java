/*
 * Copyright (c) 2019 - Manifold Systems LLC
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

package manifold.sql.schema.type;

import com.sun.tools.javac.code.Flags;
import manifold.api.gen.*;
import manifold.api.host.IModule;
import manifold.json.rt.api.*;
import manifold.rt.api.*;
import manifold.rt.api.util.Pair;
import manifold.sql.api.Column;
import manifold.sql.rt.api.*;
import manifold.sql.rt.api.OperableTxScope;
import manifold.sql.rt.impl.DefaultTxScopeProvider;
import manifold.sql.schema.api.*;
import manifold.sql.schema.jdbc.JdbcSchemaForeignKey;
import manifold.util.concurrent.LocklessLazyVar;
import org.jetbrains.annotations.NotNull;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.*;

import static manifold.api.gen.AbstractSrcClass.Kind.Class;
import static manifold.api.gen.AbstractSrcClass.Kind.Interface;
import static manifold.api.gen.SrcLinkedClass.addActualNameAnnotation;
import static manifold.rt.api.util.ManIdentifierUtil.makeIdentifier;
import static manifold.rt.api.util.ManIdentifierUtil.makePascalCaseIdentifier;

/**
 * The top-level class enclosing all the DDL types corresponding with a ".dbconfig" file.
 */
class SchemaParentType
{
  private final SchemaModel _model;

  SchemaParentType( SchemaModel model )
  {
    _model = model;
  }

  private String getFqn()
  {
    return _model.getFqn();
  }

  @SuppressWarnings( "unused" )
  boolean hasChild( String childName )
  {
    return getSchema() != null && getSchema().hasTable( childName );
  }

  private Schema getSchema()
  {
    return _model.getSchema();
  }

  void render( StringBuilder sb, JavaFileManager.Location location, IModule module, DiagnosticListener<JavaFileObject> errorHandler )
  {
    SrcLinkedClass srcClass = new SrcLinkedClass( getFqn(), Class, _model.getFile(), location, module, errorHandler )
      .addAnnotation( new SrcAnnotationExpression( DisableStringLiteralTemplates.class.getSimpleName() ) )
      .modifiers( Modifier.PUBLIC )
      .addInterface( new SrcType( SchemaType.class.getSimpleName() ) );
    addImports( srcClass );
    addDefaultScopeMethod( srcClass );
    addCommitMethod( srcClass );
    addNewScopeMethod( srcClass );
    addInnerTypes( srcClass );
    addFkColAssignMethod( srcClass );
    srcClass.render( sb, 0 );
  }

  private void addDefaultScopeMethod( SrcLinkedClass srcClass )
  {
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.PRIVATE | Modifier.STATIC )
      .name( "defaultScope" )
      .returns( new SrcType( TxScope.class.getSimpleName() ) );
    method.body( "return DefaultTxScopeProvider.instance().defaultScope(${srcClass.getName()}.class);" );
    srcClass.addMethod( method );
  }

  private void addCommitMethod( SrcLinkedClass srcClass )
  {
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.PUBLIC | Modifier.STATIC )
      .name( "commit" )
      .throwsList( new SrcType( SQLException.class.getSimpleName() ) )
      .body( "defaultScope().commit();" );
    srcClass.addMethod( method );
  }

  private void addNewScopeMethod( SrcLinkedClass srcClass )
  {
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.PUBLIC | Modifier.STATIC )
      .name( "newScope" )
      .returns( new SrcType( TxScope.class.getSimpleName() ) );
    method.body( "return ${Dependencies.class.getName()}.instance().getTxScopeProvider().newScope(${srcClass.getName()}.class);" );
    srcClass.addMethod( method );
  }

  private void addInnerTypes( SrcLinkedClass srcClass )
  {
    if( getSchema() == null )
    {
      return;
    }

    for( SchemaTable type: getSchema().getTables().values() )
    {
      addTableInterfaces( type, srcClass );
    }
  }

  private void addTableInterfaces( SchemaTable table, SrcLinkedClass enclosingType )
  {
    String identifier = getSchema().getJavaTypeName( table.getName() );
    String fqn = getFqn() + '.' + identifier;
    SrcLinkedClass srcClass = new SrcLinkedClass( fqn, enclosingType, Interface )
      .addInterface( TableRow.class.getSimpleName() )
      .modifiers( Modifier.PUBLIC );
    addActualNameAnnotation( srcClass, table.getName(), false );
    addImplClass( srcClass );
    addCreateMethods( srcClass, table );
    addReadMethods( srcClass, table );
    addDeleteMethod( srcClass );
    addBuilderType( srcClass, table );
    addBuilderMethod( srcClass, table );
    addTableInfoMethod( srcClass, table );
    addProperties( table, srcClass );
    addOneToManyMethods( table, srcClass );
    addManyToManyMethods( table, srcClass );
    enclosingType.addInnerClass( srcClass );
  }

  private void addImplClass( SrcLinkedClass interfaceType )
  {
    String identifier = "Impl";
    String fqn = getFqn() + '.' + identifier;
    SrcLinkedClass srcClass = new SrcLinkedClass( fqn, interfaceType, Class )
      .addInterface( interfaceType.getSimpleName() );

    SrcField bindingsField = new SrcField( "_bindings", TxBindings.class )
      .modifiers( Modifier.PRIVATE | Modifier.FINAL );
    srcClass.addField( bindingsField );

    SrcConstructor ctor = new SrcConstructor( srcClass )
      .modifiers( Modifier.PRIVATE )
      .addParam( new SrcParameter( "bindings", TxBindings.class )
        .addAnnotation( NotNull.class.getSimpleName() ) )
      .body( "_bindings = bindings;" );
    srcClass.addConstructor( ctor );

    SrcMethod bindingsMethod = new SrcMethod( srcClass )
      .modifiers( Modifier.PUBLIC )
      .name( "getBindings" )
      .returns( TxBindings.class )
      .body( "return _bindings;" );
    srcClass.addMethod( bindingsMethod );

    interfaceType.addInnerClass( srcClass );
  }

  private void addProperties( SchemaTable table, SrcLinkedClass srcClass )
  {
    for( Map.Entry<SchemaTable, List<SchemaForeignKey>> entry : table.getForeignKeys().entrySet() )
    {
      List<SchemaForeignKey> fk = entry.getValue();
      for( SchemaForeignKey sfk : fk )
      {
        addFkProperty( srcClass, sfk );
      }
    }
    for( SchemaColumn col: table.getColumns().values() )
    {
      addProperty( srcClass, col );
    }
  }

  private void addTableInfoMethod( SrcLinkedClass srcClass, SchemaTable table )
  {
    SrcField tableInfoField = new SrcField( "myTableInfo", new SrcType( LocklessLazyVar.class.getSimpleName() ).addTypeParam( TableInfo.class ) );
    StringBuilder sb = new StringBuilder( "LocklessLazyVar.make(() -> {\n" );
    sb.append( "      LinkedHashMap<String, Integer> allCols = new LinkedHashMap<>();\n" );
    for( Map.Entry<String, SchemaColumn> entry : table.getColumns().entrySet() )
    {
      //noinspection unused
      String colName = entry.getKey();
      //noinspection unused
      int jdbcType = entry.getValue().getJdbcType();
      sb.append( "      allCols.put(\"$colName\", $jdbcType);\n");
    }
    sb.append( "      HashSet<String> pkCols = new HashSet<>();\n" );
    for( SchemaColumn pkCol : table.getPrimaryKey() )
    {
      //noinspection unused
      String pkColName = pkCol.getName();
      sb.append( "      pkCols.add(\"$pkColName\");\n\n" );
    }
    sb.append( "      HashSet<String> ukCols = new HashSet<>();\n" );
    for( Map.Entry<String, List<SchemaColumn>> entry : table.getNonNullUniqueKeys().entrySet() )
    {
      // just need one
      for( SchemaColumn ukCol : entry.getValue() )
      {
        //noinspection unused
        String ukColName = ukCol.getName();
        sb.append( "      ukCols.add(\"$ukColName\");\n" );
      }
      break;
    }
    //noinspection unused
    String ddlTableName = table.getName();
    sb.append( "      return new TableInfo(\"$ddlTableName\", pkCols, ukCols, allCols);\n" );
    sb.append( "    });\n" );
    tableInfoField.initializer( sb.toString() );
    srcClass.addField( tableInfoField );

    SrcMethod tableInfoMethod = new SrcMethod( srcClass )
      .modifiers( Flags.DEFAULT )
      .name( "tableInfo" )
      .returns( TableInfo.class );
    tableInfoMethod.body( "return myTableInfo.get();" );
    srcClass.addMethod( tableInfoMethod );
  }

  private void addBuilderMethod( SrcLinkedClass srcClass, SchemaTable table )
  {
    addBuilderMethod( srcClass, table, true );
    addBuilderMethod( srcClass, table, false );
  }
  private void addBuilderMethod( SrcLinkedClass srcClass, SchemaTable table, boolean fkRefs )
  {
    if( fkRefs && !hasRequiredForeignKeys( table ) )
    {
      return;
    }

    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.STATIC )
      .name( "builder" )
      .returns( new SrcType( "Builder" ) );
    if( fkRefs )
    {
      addRequiredParametersUsingFkRefs( table, method );
    }
    else
    {
      addRequiredParameters( table, method );
    }
    srcClass.addMethod( method );

    StringBuilder sb = new StringBuilder();
    sb.append( "return new Builder() {\n" );
    sb.append( "        Bindings _bindings = new DataBindings();\n" );
    sb.append( "        {\n" );
    if( fkRefs )
    {
      initFromParametersUsingFkRefs( table, sb, "_bindings" );
    }
    else
    {
      initFromParameters( table, sb, "_bindings" );
    }
    sb.append( "        }\n" );

    sb.append( "        @Override public Bindings getBindings() { return _bindings; }\n" );
    sb.append( "      };" );
    method.body( sb.toString() );
  }

  private void addCreateMethods( SrcLinkedClass srcClass, SchemaTable table )
  {
    addCreateMethods( srcClass, table, true );
    addCreateMethods( srcClass, table, false );
  }
  private void addCreateMethods( SrcLinkedClass srcClass, SchemaTable table, boolean fkRefs )
  {
    if( fkRefs && !hasRequiredForeignKeys( table ) )
    {
      return;
    }

    String tableName = getTableFqn( table );
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.STATIC )
      .name( "create" )
      .returns( new SrcType( tableName ) );
    if( fkRefs )
    {
      addRequiredParametersUsingFkRefs( table, method );
    }
    else
    {
      addRequiredParameters( table, method );
    }
    StringBuilder sb = new StringBuilder();
    sb.append( "return create(defaultScope()" );
    sb.append( method.getParameters().isEmpty() ? "" : ", " );
    method.forwardParameters( sb );
    sb.append( ");" );
    method.body( sb.toString() );
    srcClass.addMethod( method );


    method = new SrcMethod( srcClass )
      .modifiers( Modifier.STATIC )
      .name( "create" )
      .returns( new SrcType( tableName ) )
      .addParam( new SrcParameter( "txScope", new SrcType( TxScope.class.getSimpleName() ) )
        .addAnnotation( NotNull.class.getSimpleName() ) );
    if( fkRefs )
    {
      addRequiredParametersUsingFkRefs( table, method );
    }
    else
    {
      addRequiredParameters( table, method );
    }
    srcClass.addMethod( method );

    sb = new StringBuilder();
    sb.append( "DataBindings args = new DataBindings();\n" );
    if( fkRefs )
    {
      initFromParametersUsingFkRefs( table, sb, "args" );
    }
    else
    {
      initFromParameters( table, sb, "args" );
    }
    sb.append( "      TxBindings bindings = new BasicTxBindings(txScope, TxKind.Insert, args);\n" );
    sb.append( "      $tableName tableRow = new Impl(bindings);\n" );
    sb.append( "      tableRow.getBindings().setOwner(tableRow);\n" );
    sb.append( "      ((OperableTxScope)txScope).addRow(tableRow);\n" );
    sb.append( "      return tableRow;" );
    method.body( sb.toString() );
  }

  private boolean hasRequiredForeignKeys( SchemaTable table )
  {
    // at least one non-null foreign key
    return table.getForeignKeys().values().stream()
      .anyMatch( sfks -> sfks.stream()
        .anyMatch( sfk -> sfk.getColumns().stream()
          .anyMatch( c -> isRequired( c ) ) ) );
  }

  private void initFromParametersUsingFkRefs( SchemaTable table, StringBuilder sb, @SuppressWarnings( "unused" ) String bindingsVar )
  {
    Set<SchemaColumn> fkCovered = new HashSet<>();
    for( Map.Entry<SchemaTable, List<SchemaForeignKey>> entry : table.getForeignKeys().entrySet() )
    {
      List<SchemaForeignKey> fk = entry.getValue();
      for( SchemaForeignKey sfk : fk )
      {
        List<SchemaColumn> fkCols = sfk.getColumns();
        if( fkCols.stream().anyMatch( c -> isRequired( c ) ) )
        {
          //noinspection unused
          String fkParamName = makePascalCaseIdentifier( sfk.getName(), false );
          for( SchemaColumn fkCol : fkCols )
          {
            //noinspection unused
            String colName = fkCol.getName();
            //noinspection unused
            String keyColName = fkCol.getForeignKey().getName();
            sb.append( "assignFkBindingValues($fkParamName, \"$fkParamName\", \"$keyColName\", \"$colName\", $bindingsVar);" );
          }
          fkCovered.addAll( fkCols );
        }
      }
    }
    for( SchemaColumn col: table.getColumns().values() )
    {
      if( isRequired( col ) && !fkCovered.contains( col ) )
      {
        //noinspection unused
        String colName = col.getName();
        //noinspection unused
        String paramName = makePascalCaseIdentifier( col.getName(), false );
        sb.append( "$bindingsVar.put(\"$colName\", $paramName);\n" );
      }
    }
  }

  private void initFromParameters( SchemaTable table, StringBuilder sb, @SuppressWarnings( "unused" ) String bindingsVar )
  {
    for( SchemaColumn col: table.getColumns().values() )
    {
      if( isRequired( col ) )
      {
        //noinspection unused
        String colName = col.getName();
        //noinspection unused
        String paramName = makePascalCaseIdentifier( col.getName(), false );
        sb.append( "$bindingsVar.put(\"$colName\", $paramName);\n" );
      }
    }
  }

  private void addRequiredParametersUsingFkRefs( SchemaTable table, AbstractSrcMethod method )
  {
    // Note, parameters are added in order of appearance as they are with just columns, fks consolidate params

    Set<SchemaForeignKey> visited = new HashSet<>();
    for( SchemaColumn col: table.getColumns().values() )
    {
      if( isRequired( col ) )
      {
        if( col.getForeignKey() != null )
        {
          // Add fk ref param

          Set<SchemaForeignKey> sfkSet = getfk( table, col );
          for( SchemaForeignKey sfk : sfkSet )
          {
            if( !visited.contains( sfk ) )
            {
              visited.add( sfk );
              addFkParam( method, sfk );
            }
          }
        }
        else
        {
          // Add column param

          SrcParameter param = new SrcParameter( makePascalCaseIdentifier( col.getName(), false ), col.getType() );
          if( !col.getType().isPrimitive() )
          {
            param.addAnnotation( NotNull.class.getSimpleName() );
          }
          method.addParam( param );
        }
      }
    }
  }

  private void addFkParam( AbstractSrcMethod method, SchemaForeignKey sfk )
  {
    List<SchemaColumn> fkCols = sfk.getColumns();
    if( fkCols.stream().anyMatch( c -> isRequired( c ) ) )
    {
      String tableFqn = getTableFqn( sfk.getReferencedTable() );
      SrcType srcType = new SrcType( tableFqn );
      method.addParam( new SrcParameter( makePascalCaseIdentifier( sfk.getName(), false ), srcType )
        .addAnnotation( NotNull.class.getSimpleName() ) );
    }
  }

  private Set<SchemaForeignKey> getfk( SchemaTable table, SchemaColumn col )
  {
    Set<SchemaForeignKey> sfkSet = new LinkedHashSet<>();
    for( List<SchemaForeignKey> sfks : table.getForeignKeys().values() )
    {
      for( SchemaForeignKey sfk : sfks )
      {
        for( SchemaColumn column : sfk.getColumns() )
        {
          if( column == col )
          {
            sfkSet.add( sfk );
            break;
          }
        }
      }
    }
    return sfkSet;
  }

  private void addRequiredParameters( SchemaTable table, AbstractSrcMethod method )
  {
    for( SchemaColumn col: table.getColumns().values() )
    {
      if( isRequired( col ) )
      {
        SrcParameter param = new SrcParameter( makePascalCaseIdentifier( col.getName(), false ), col.getType() );
        if( !col.getType().isPrimitive() )
        {
          param.addAnnotation( NotNull.class.getSimpleName() );
        }
        method.addParam( param );
      }
    }
  }

  private void addBuilderType( SrcLinkedClass enclosingType, SchemaTable table )
  {
    String fqn = enclosingType.getName() + ".Builder";
    SrcLinkedClass srcInterface = new SrcLinkedClass( fqn, enclosingType, Interface )
      .addInterface( new SrcType( SchemaBuilder.class.getSimpleName() ).addTypeParam( getTableFqn( table ) ) );
    enclosingType.addInnerClass( srcInterface );
    addWithMethods( srcInterface, table );
    addBuildMethods( srcInterface, table );
  }

  private void addBuildMethods( SrcLinkedClass srcInterface, SchemaTable table )
  {
    String tableName = getTableFqn( table );
    SrcMethod method = new SrcMethod( srcInterface )
      .modifiers( Flags.DEFAULT )
      .name( "build" )
      .returns( new SrcType( tableName ) )
      .body( "return build(defaultScope());" );
    srcInterface.addMethod( method );

    method = new SrcMethod( srcInterface )
      .modifiers( Flags.DEFAULT )
      .name( "build" )
      .addParam( new SrcParameter( "txScope", new SrcType( TxScope.class.getSimpleName() ) )
        .addAnnotation( NotNull.class.getSimpleName() ) )
      .returns( new SrcType( tableName ) );
    srcInterface.addMethod( method );
    method.body(
        "BasicTxBindings bindings = new BasicTxBindings(txScope, TxKind.Insert, Builder.this.getBindings());\n" +
        "        $tableName tableRow = new Impl(bindings);\n" +
        "        tableRow.getBindings().setOwner(tableRow);\n" +
        "        ((OperableTxScope)txScope).addRow(tableRow);\n" +
        "        return tableRow;" );
  }

  private void addWithMethods( SrcLinkedClass srcClass, SchemaTable table )
  {
    for( Map.Entry<SchemaTable, List<SchemaForeignKey>> entry : table.getForeignKeys().entrySet() )
    {
      List<SchemaForeignKey> fk = entry.getValue();
      for( SchemaForeignKey sfk : fk )
      {
        List<SchemaColumn> fkCols = sfk.getColumns();
        if( fkCols.stream().noneMatch( c -> isRequired( c ) ) )
        {
          String tableFqn = getTableFqn( sfk.getReferencedTable() );
          SrcType srcType = new SrcType( tableFqn );

          //noinspection unused
          String propName = makePascalCaseIdentifier( sfk.getName(), true );
          SrcMethod withMethod = new SrcMethod()
            .modifiers( Flags.DEFAULT )
            .name( "with$propName" )
            .addParam( "${'$'}value", srcType )
            .returns( new SrcType( srcClass.getSimpleName() ) );
          addActualNameAnnotation( withMethod, sfk.getActualName(), true );
          StringBuilder sb = new StringBuilder();
          //noinspection unused
          for( SchemaColumn fkCol : fkCols )
          {
            //noinspection unused
            String colName = fkCol.getName();
            //noinspection unused
            String keyColName = fkCol.getForeignKey().getName();
            sb.append( "assignFkBindingValues(${'$'}value, \"${'$'}value\", \"$keyColName\", \"$colName\", getBindings());" );
          }
          sb.append( "return this;" );
          withMethod.body( sb.toString() );
          srcClass.addMethod( withMethod );
        }
      }
    }

    for( SchemaColumn col: table.getColumns().values() )
    {
      if( !isRequired( col ) )
      {
        //noinspection unused
        String actualName = col.getName();
        //noinspection unused
        String propName = makePascalCaseIdentifier( actualName, true );
        SrcMethod withMethod = new SrcMethod()
          .modifiers( Flags.DEFAULT )
          .name( "with$propName" )
          .addParam( "${'$'}value", col.getType() )
          .returns( new SrcType( srcClass.getSimpleName() ) );
        addActualNameAnnotation( withMethod, actualName, true );
        withMethod.body( "getBindings().put(\"$actualName\", ${'$'}value); return this;" );
        srcClass.addMethod( withMethod );
      }
    }
  }

  private boolean isRequired( SchemaColumn col )
  {
    return !col.isNullable() &&
      !col.isGenerated() &&
      !col.isAutoIncrement() &&
      col.getDefaultValue() == null &&
      !col.isSqliteRowId();
  }

  private void addImports( SrcLinkedClass srcClass )
  {
    srcClass.addImport( Bindings.class );
    srcClass.addImport( TxBindings.class );
    srcClass.addImport( TxScope.class );
    srcClass.addImport( OperableTxScope.class );
    srcClass.addImport( BasicTxBindings.class );
    srcClass.addImport( BasicTxBindings.TxKind.class );
    srcClass.addImport( DataBindings.class );
    srcClass.addImport( TableRow.class );
    srcClass.addImport( TableInfo.class );
    srcClass.addImport( SchemaType.class );
    srcClass.addImport( SchemaBuilder.class );
    srcClass.addImport( QueryContext.class );
    srcClass.addImport( CrudProvider.class );
    srcClass.addImport( Runner.class );
    srcClass.addImport( TxScopeProvider.class );
    srcClass.addImport( DefaultTxScopeProvider.class );
    srcClass.addImport( KeyRef.class );
    srcClass.addImport( LocklessLazyVar.class );
    srcClass.addImport( Collections.class );
    srcClass.addImport( LinkedHashMap.class );
    srcClass.addImport( List.class );
    srcClass.addImport( Map.class );
    srcClass.addImport( Set.class );
    srcClass.addImport( HashSet.class );
    srcClass.addImport( SQLException.class );
    srcClass.addImport( NotNull.class );
    srcClass.addImport( ActualName.class );
    srcClass.addImport( DisableStringLiteralTemplates.class );
  }

  private void addFkProperty( SrcLinkedClass srcClass, SchemaForeignKey sfk )
  {
    SchemaTable table = sfk.getReferencedTable();
    String tableFqn = getTableFqn( table );

    SrcType type = new SrcType( tableFqn );
    String name = sfk.getName();
    String propName = makePascalCaseIdentifier( name, true );
    SrcMethod fkFetchMethod = new SrcMethod( srcClass )
      .name( "get" + propName )
      .modifiers( Flags.DEFAULT )
      .returns( type );
    StringBuilder sb = new StringBuilder();
    sb.append( "DataBindings paramBindings = new DataBindings();\n" );
    List<SchemaColumn> columns = sfk.getColumns();
    for( int i = 0; i < columns.size(); i++ )
    {
      SchemaColumn col = columns.get( i );
      //noinspection unused
      Column referencedCol = col.getForeignKey();
      if( i == 0 )
      {
        // get assigned ref that is newly created and not committed yet
        sb.append( "    Object maybeRef = getBindings().get(\"${col.getName()}\");\n" )
          .append( "    if(maybeRef instanceof ${TableRow.class.getSimpleName()}) {return ($tableFqn)maybeRef;}\n" );
      }
      sb.append( "    paramBindings.put(\"${referencedCol.getName()}\", getBindings().get(\"${col.getName()}\"));\n" );
    }

    //noinspection unused
    String jdbcParamTypes = getJdbcParamTypes( sfk.getColumns() );
    //noinspection unused
    String configName = _model.getDbConfig().getName();
    sb.append( "    return ${Dependencies.class.getName()}.instance().getCrudProvider().readOne(" +
      "new QueryContext<$tableFqn>(getBindings().getTxScope(), $tableFqn.class, \"${table.getName()}\", $jdbcParamTypes, paramBindings, \"$configName\", " +
      "rowBindings -> new $tableFqn.Impl(rowBindings)));" );
    fkFetchMethod.body( sb.toString() );
    addActualNameAnnotation( fkFetchMethod, name, true );
    srcClass.addMethod( fkFetchMethod );

    SrcMethod fkSetter = new SrcMethod( srcClass )
      .modifiers( Flags.DEFAULT )
      .name( "set" + propName )
      .addParam( "ref", new SrcType( tableFqn ) );
    for( SchemaColumn fkCol : sfk.getColumns() )
    {
      //noinspection unused
      String colName = fkCol.getName();
      //noinspection unused
      String keyColName = fkCol.getForeignKey().getName();
      fkSetter.body( "assignFkBindingValues(ref, \"$propName\", \"$keyColName\", \"$colName\", getBindings());" );
    }
    addActualNameAnnotation( fkSetter, name, true );
    srcClass.addMethod( fkSetter );
  }

  private void addFkColAssignMethod( SrcLinkedClass srcClass )
  {
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.PRIVATE | Modifier.STATIC )
      .name( "assignFkBindingValues" )
      .addParam( "ref", new SrcType( TableRow.class ) )
      .addParam( "propName", new SrcType( String.class ) )
      .addParam( "keyColName", new SrcType( String.class ) )
      .addParam( "colName", new SrcType( String.class ) )
      .addParam( "bindings", new SrcType( Bindings.class ) );
      method.body( "if(ref == null) throw new NullPointerException(\"Expecting non-null value for: \" + propName );\n" +
        "    Object keyColValue = ref.getBindings().get(keyColName);\n" +
        "    bindings.put(colName, keyColValue != null ? keyColValue : new KeyRef(ref, keyColName));" );
    srcClass.addMethod( method );
  }

  private void addProperty( SrcLinkedClass srcInterface, SchemaColumn col )
  {
    Class<?> type = col.getType();
    String name = col.getName();

    SrcType propType = makeSrcType( type );
    String propName = makePascalCaseIdentifier( name, true );
    //noinspection unused
    String colName = makeIdentifier( name );

    SrcGetProperty getter = new SrcGetProperty( propName, propType );
    getter.modifiers( Flags.DEFAULT );
    StringBuilder retType = new StringBuilder();
    propType.render( retType, 0, false ); // calling render to include array "[]"
    if( col.getForeignKey() != null )
    {
      getter.body( "Object value = getBindings().get(\"$colName\");\n" +
                   "    return value instanceof ${TableRow.class.getSimpleName()} ? null : ($retType)value;" );
    }
    else
    {
      getter.body( "return ($retType)getBindings().get(\"$colName\");" );
    }
    addActualNameAnnotation( getter, name, true );
    srcInterface.addGetProperty( getter );

    if( !col.isGenerated() && !col.isAutoIncrement() )
    {
      SrcSetProperty setter = new SrcSetProperty( propName, propType )
        .modifiers( Flags.DEFAULT );
      setter.body( "getBindings().put(\"$colName\", ${'$'}value);" );
      addActualNameAnnotation( setter, name, true );
      srcInterface.addSetProperty( setter );
    }
  }

  // Foo foo = Foo.create(txScope, ...);
  // Foo foo = Foo.read(txScope, ...);
  // foo.delete();
  //...
  // txScope.commit();

  private void addReadMethods( SrcLinkedClass srcClass, SchemaTable table )
  {
    String tableFqn = getTableFqn( table );
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Modifier.STATIC )
      .name( "read" )
      .returns( new SrcType( tableFqn ) );
    List<SchemaColumn> whereCols = addSelectParameters( table, method );
    if( whereCols.isEmpty() )
    {
      // no pk and no pk, no read method, instead use type-safe sql query :)
      return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append( "return read(defaultScope()" );
    sb.append( method.getParameters().isEmpty() ? "" : ", " );
    method.forwardParameters( sb );
    sb.append( ");" );
    method.body( sb.toString() );
    srcClass.addMethod( method );


    method = new SrcMethod( srcClass )
      .modifiers( Modifier.STATIC )
      .name( "read" )
      .returns( new SrcType( tableFqn ) )
      .addParam( new SrcParameter( "txScope", new SrcType( TxScope.class.getSimpleName() ) )
        .addAnnotation( NotNull.class.getSimpleName() ) );
    whereCols = addSelectParameters( table, method );
    //noinspection unused
    String jdbcParamTypes = getJdbcParamTypes( whereCols );
    //noinspection unused
    String configName = _model.getDbConfig().getName();
    sb = new StringBuilder();
    sb.append( "DataBindings paramBindings = new DataBindings();\n" );
    for( SchemaColumn col : whereCols )
    {
      //noinspection unused
      String paramName = makePascalCaseIdentifier( col.getName(), false );
      sb.append( "    paramBindings.put(\"${col.getName()}\", $paramName);\n" );
    }
    sb.append( "    return ${Dependencies.class.getName()}.instance().getCrudProvider().readOne(new QueryContext<$tableFqn>(txScope, $tableFqn.class,\n" +
      "      \"${table.getName()}\", $jdbcParamTypes, paramBindings, \"$configName\",\n" +
      "      rowBindings -> new $tableFqn.Impl(rowBindings)));" );
    method.body( sb.toString() );
    srcClass.addMethod( method );
  }

  private List<SchemaColumn> addSelectParameters( SchemaTable table, AbstractSrcMethod method )
  {
    List<SchemaColumn> pk = table.getPrimaryKey();
    if( !pk.isEmpty() )
    {
      for( SchemaColumn col : pk )
      {
        method.addParam( makePascalCaseIdentifier( col.getName(), false ), col.getType() );
      }
      return pk;
    }
    else
    {
      for( Map.Entry<String, List<SchemaColumn>> entry : table.getNonNullUniqueKeys().entrySet() )
      {
        for( SchemaColumn col : entry.getValue() )
        {
          method.addParam( makePascalCaseIdentifier( col.getName(), false ), col.getType() );
        }
        return entry.getValue();
      }
    }
    return Collections.emptyList();
  }

  private void addDeleteMethod( SrcLinkedClass srcClass )
  {
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Flags.DEFAULT )
      .name( "delete" )
      .addParam( "delete", boolean.class );
    method.body( "getBindings().setDelete(delete);" );
    srcClass.addMethod( method );
  }

  private void addOneToManyMethods( SchemaTable table, SrcLinkedClass srcClass )
  {
    for( SchemaForeignKey sfk : table.getOneToMany() )
    {
      addOneToManyFetcher( srcClass, sfk );
//      addAddToMany( srcClass, sfk );
//      addRemoveFromMany( srcClass, sfk );
    }
  }
  private void addOneToManyFetcher( SrcLinkedClass srcClass, SchemaForeignKey fkToThis )
  {
    // e.g., for a Post that has comments there will be a link table PostComment where, fetchPostCommentList() { SELECT * FROM post_comment WHERE post_comment.post_id = :post_id }
    //noinspection unused
    String tableFqn = getTableFqn( fkToThis.getOwnTable() );
    //noinspection unused
    SrcType type = new SrcType( tableFqn );
    //noinspection unused
    String propName = makePascalCaseIdentifier( fkToThis.getQualifiedName(), true );
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Flags.DEFAULT )
      .name( "fetch${propName}s" )
      .returns( new SrcType( "List<$tableFqn>" ) );
    //noinspection unused
    String configName = fkToThis.getReferencedTable().getSchema().getDbConfig().getName();
    //noinspection unused
    String tableName = fkToThis.getOwnTable().getName();
    //noinspection unused
    String jdbcParamTypes = getJdbcParamTypes( fkToThis.getColumns() );
    StringBuilder sb = new StringBuilder();
    sb.append( "DataBindings paramBindings = new DataBindings();\n" );
    for( SchemaColumn col : fkToThis.getColumns() )
    {
      //noinspection unused
      Column referencedCol = col.getForeignKey();
      sb.append( "    Object value = getBindings().get(\"${col.getName()}\");\n" )
        .append( "    if(value instanceof ${TableRow.class.getSimpleName()}) return Collections.emptyList();\n" )
        .append( "    paramBindings.put(\"${referencedCol.getName()}\", value);\n" );
    }
    sb.append( "    return ${Dependencies.class.getName()}.instance().getCrudProvider().readMany(" +
      "      new QueryContext<$tableFqn>(getBindings().getTxScope(), $tableFqn.class, \"$tableName\", $jdbcParamTypes, paramBindings, \"$configName\", " +
      "      rowBindings -> new $tableFqn.Impl(rowBindings)));" );
    method.body( sb.toString() );
    srcClass.addMethod( method );
  }

  private void addManyToManyMethods( SchemaTable table, SrcLinkedClass srcClass )
  {
    for( Pair<SchemaColumn, SchemaColumn> uk : table.getManyToMany() )
    {
      addManyToManyFetcher( srcClass, table, uk );
    }
  }
  private void addManyToManyFetcher( SrcLinkedClass srcClass, SchemaTable table, Pair<SchemaColumn, SchemaColumn> uk )
  {
    SchemaColumn fkToMe;
    SchemaColumn fkToOther;
    if( uk.getFirst().getForeignKey().getTable() == table )
    {
      fkToMe = uk.getFirst();
      fkToOther = uk.getSecond();
    }
    else
    {
      fkToMe = uk.getSecond();
      fkToOther = uk.getFirst();
    }

    // e.g., select * from CATEGORY join FILM_CATEGORY on CATEGORY.CATEGORY_ID = FILM_CATEGORY.CATEGORY_ID where FILM_CATEGORY.FILM_ID = :FILM_ID;
    //noinspection unused
    String tableFqn = getTableFqn( fkToOther.getForeignKey().getTable() );
    //noinspection unused
    String propName = makePascalCaseIdentifier( JdbcSchemaForeignKey.removeId( fkToOther.getName() ) + "_ref", true );
    SrcMethod method = new SrcMethod( srcClass )
      .modifiers( Flags.DEFAULT )
      .name( "fetch${propName}s" )
      .returns( new SrcType( "List<$tableFqn>" ) );
    //noinspection unused
    String configName = fkToOther.getForeignKey().getTable().getSchema().getDbConfig().getName();
    //noinspection unused
    String otherTable = fkToOther.getForeignKey().getTable().getName();
    //noinspection unused
    String linkTable = fkToOther.getTable().getName();

    //noinspection unused
    String sql = "select * from $otherTable " +
      "join $linkTable on ${makeJoinOn( fkToOther )} " +
      "where ${makeJoinWhere( fkToMe )}";

    //noinspection unused
    String jdbcParamTypes = getJdbcParamTypes( Collections.singletonList( fkToMe.getForeignKey() ) );

    StringBuilder sb = new StringBuilder();
    sb.append( "DataBindings paramBindings = new DataBindings();\n" );
    //noinspection unused
    SchemaColumn referencedCol = fkToMe.getForeignKey();
    sb.append( "      Object value = getBindings().get(\"${fkToMe.getName()}\");\n" )
      .append( "      if(value instanceof ${TableRow.class.getSimpleName()}) return Collections.emptyList();\n" )
      .append( "      paramBindings.put(\"${referencedCol.getName()}\", value);\n" );
    sb.append( "      return new ${Runner.class.getName()}<$tableFqn>(\n" +
      "          new QueryContext<$tableFqn>(getBindings().getTxScope(), $tableFqn.class, null, $jdbcParamTypes, paramBindings, \"$configName\", \n" +
      "          rowBindings -> new $tableFqn.Impl(rowBindings)), \"$sql\")\n" +
      "        .fetch().toList();" );
    method.body( sb.toString() );
    srcClass.addMethod( method );
  }

  @SuppressWarnings( "unused" )
  private String makeJoinOn( SchemaColumn fkToOther )
  {
    StringBuilder sb = new StringBuilder();
    SchemaColumn refCol = fkToOther.getForeignKey();
    sb.append( refCol.getTable().getName() ).append( '.' ).append( refCol.getName() ).append( " = " )
      .append( fkToOther.getTable().getName() ).append( '.' ).append( fkToOther.getName() );
    return sb.toString();
  }

  @SuppressWarnings( "unused" )
  private String makeJoinWhere( SchemaColumn fkToMe )
  {
    StringBuilder sb = new StringBuilder();
    SchemaColumn refCol = fkToMe.getForeignKey();
    sb.append( fkToMe.getTable().getName() ).append( '.' ).append( fkToMe.getName() ).append( " = ?" );
    return sb.toString();
  }

//  private void addAddToMany( SrcLinkedClass srcClass, SchemaForeignKey fkToThis )
//  {
//    //todo: add add<fk-to-this>() method.
//    // For one-to-many e.g., for a Post table that has Comments, addComment(String text) { Comment.create(this, text)... }
//    // This method will have the same parameters as the fk table's create method, minus the fk's fk to this table's id, which we can set directly in the body of the method
//    // ...or not.  After further thought, I think it's better to just rely on create() or, if an existing object is changing it's fk, then let that happen naturally.
//    // no need for addToXxxRefs()
//    // For many-to-many e.g., for a Blog table that has Subscribers via link table BlogSubscriber, addBlogSubscriber(Subscriber s)
//    // Same basic logic, no need for addTo/removeFrom stuff. Better to let it happen naturally.
//  }
//  // many-to-many is special with remove. since it involves another instance, we delete via id, whereas with a Comment a simple delete(), no need for a removeXxx method
//  private void addRemoveFromMany()
//  {
//    //todo: add remove<fk-to-this>() method.
//    // e.g., for a Blog table that has Subscribers there will be a link table BlogSubscriber,
//    // removeBlogSubscriber(Subscriber s) { delete() method uses a local delete stmt: delete from BlogSubscriber where blog_id = :blog_id AND subscriber_id = :subscriber_id }
//  }

  private String getJdbcParamTypes( List<SchemaColumn> parameters )
  {
    StringBuilder sb = new StringBuilder( "new int[]{");
    for( int i = 0; i < parameters.size(); i++ )
    {
      Column p = parameters.get( i );
      if( i > 0 )
      {
        sb.append( "," );
      }
      sb.append( p.getJdbcType() );
    }
    return sb.append( "}" ).toString();
  }

  // qualifying name with outer class name (config name) to prevent collisions with other class names that could be imported
  private String getTableFqn( SchemaTable table )
  {
//    String schemaPackage = _model.getDbConfig().getSchemaPackage();
    String configName = table.getSchema().getDbConfig().getName();
//    return schemaPackage + "." + configName + "." + getTableSimpleTypeName( table );
    return configName + "." + getTableSimpleTypeName( table );
  }

  private String getTableSimpleTypeName( SchemaTable table )
  {
    return table.getSchema().getJavaTypeName( table.getName() );
  }

  private SrcType makeSrcType( Class<?> type )
  {
    String typeName = getJavaName( type );
    SrcType srcType = new SrcType( typeName );
    srcType.setPrimitive( type.isPrimitive() );
    return srcType;
  }

  private String getJavaName( Class<?> cls )
  {
    if( cls == String.class )
    {
      return String.class.getSimpleName();
    }
    return cls.getTypeName();
  }
}

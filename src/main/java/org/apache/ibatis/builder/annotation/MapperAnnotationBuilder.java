/*
 *    Copyright 2009-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.annotation;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.binding.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMappingConstructorResolver;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.extension.CrudMapper;
import org.apache.ibatis.internal.util.ClassUtils;
import org.apache.ibatis.internal.util.LambdaUtils;
import org.apache.ibatis.internal.util.function.ThrowableBiFunction;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.NameMapping;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

  // @formatter:off
  @SuppressWarnings("unchecked")
  private static final Class<? extends Annotation>[] statementAnnotationTypes =
    new Class[] {
      Select.class,
      Update.class,
      Insert.class,
      Delete.class,
      SelectProvider.class,
      UpdateProvider.class,
      InsertProvider.class,
      DeleteProvider.class
    };
  // @formatter:on

  private final Configuration configuration;
  private final MapperBuilderAssistant assistant;
  private final Class<?> type;
  private final String namespace;

  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    this.namespace = getNamespace(type);
    this.assistant = new MapperBuilderAssistant(configuration, getMapperResource(type));
    this.configuration = configuration;
    this.type = type;
  }

  private static String getMapperResource(Class<?> type) {
    return type.getName().replace('.', '/') + ".java (best guess)";
  }

  public static String getNamespace(Class<?> mapperClass) {
    Objects.requireNonNull(mapperClass, "mapper class is null.");
    Mapper mapperClassAnnotation = mapperClass.getAnnotation(Mapper.class);
    String namespace = mapperClass.getName();
    if (mapperClassAnnotation != null) {
      String specifiedNamespace = mapperClassAnnotation.namespace();
      if (!specifiedNamespace.isEmpty()) {
        namespace = specifiedNamespace;
      }
    }
    return namespace;
  }

  public void parse() {
    parse(this.namespace, this.type);
  }

  protected void parse(String namespace, Class<?> type) {
    final String resource = type.toString();
    if (configuration.isResourceLoaded(resource)) {
      return;
    }

    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace
    if (!configuration.isResourceLoaded("namespace:" + namespace)) {
      loadXmlResource(namespace, type);
    }

    configuration.addLoadedResource(resource);
    assistant.setCurrentNamespace(namespace);
    parseCache(this.assistant, type);
    parseCacheRef(this.assistant, type);

    registerTable(type);

    if (type.isAnnotationPresent(NamedResultMap.class)) {
      parseNamedResultMap(namespace, type.getAnnotation(NamedResultMap.class));
    }

    if (type.isAnnotationPresent(NamedResultMaps.class)) {
      parseNamedResultMaps(namespace, type);
    }
    final String databaseId = configuration.getDatabaseId();
    for (Method method : type.getMethods()) {
      if (!canHaveStatement(method)) {
        continue;
      }
      if (getAnnotationWrapper(databaseId, method, false, Select.class, SelectProvider.class).isPresent()
          && method.getAnnotation(ResultMap.class) == null) {
        parseResultMap(namespace, type, method);
      }
      try {
        parseStatement(type, method);
      } catch (IncompleteElementException e) {
        configuration.addIncompleteMethod(new MethodResolver(this, type, method));
      }
    }

    configuration.parsePendingMethods(false);
  }

  protected void registerTable(Class<?> mapperClass) {
    if (CrudMapper.class.isAssignableFrom(mapperClass)) {
      Type type = TypeParameterResolver.resolveClassTypeParams(CrudMapper.class, mapperClass)[0];
      if (type instanceof Class) {
        configuration.registerTable((Class<?>) type);
      }
    }
  }

  private static boolean canHaveStatement(Method method) {
    // issue #237
    return !method.isBridge() && !method.isDefault();
  }

  protected void loadXmlResource(String namespace, Class<?> type) {
    final String xmlResource = namespace.replace('.', '/') + ".xml";
    // #1347
    InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
    if (inputStream == null) {
      // Search XML mapper that is not in the module but in the classpath.
      // Note: resource is not required
      inputStream = Resources.getResourceAsStreamOrElseNull(type.getClassLoader(), xmlResource);
    }
    if (inputStream != null) {
      XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource,
          configuration.getSqlFragments(), type);
      xmlParser.parse();
    }
  }

  public Configuration getConfiguration() {
    return assistant.getConfiguration();
  }

  protected void parseCache(MapperBuilderAssistant assistant, Class<?> type) {
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    if (cacheDomain != null) {
      Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
      Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
      Properties props = convertToProperties(cacheDomain.properties(), configuration.getVariables());
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size,
          cacheDomain.readWrite(), cacheDomain.blocking(), props);
    }
  }

  private Properties convertToProperties(Property[] properties, Properties variables) {
    if (properties.length == 0) {
      return null;
    }
    Properties props = new Properties();
    for (Property property : properties) {
      props.setProperty(property.name(), PropertyParser.parse(property.value(), variables));
    }
    return props;
  }

  protected void parseCacheRef(MapperBuilderAssistant assistant, Class<?> type) {
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    if (cacheDomainRef != null) {
      Class<?> refType = cacheDomainRef.value();
      String refName = cacheDomainRef.name();
      if (refType == void.class && refName.isEmpty()) {
        throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
      }
      if (refType != void.class && !refName.isEmpty()) {
        throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
      }
      String namespace = refType != void.class ? refType.getName() : refName;
      try {
        assistant.useCacheRef(namespace);
      } catch (IncompleteElementException e) {
        assistant.addIncompleteCacheRef(namespace);
      }
    }
  }

  private String parseResultMap(String namespace, Class<?> type, Method method) {
    Class<?> returnType = getReturnType(method, type);
    Arg[] args = method.getAnnotationsByType(Arg.class);
    Result[] results = method.getAnnotationsByType(Result.class);
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    String resultMapId = generateResultMapName(namespace, method);
    applyResultMap(resultMapId, returnType, args, results, typeDiscriminator);
    return resultMapId;
  }

  protected void parseNamedResultMaps(String namespace, Class<?> type) {
    NamedResultMaps namedResultMaps = type.getAnnotation(NamedResultMaps.class);
    for (NamedResultMap namedResultMap : namedResultMaps.value()) {
      parseNamedResultMap(namespace, namedResultMap);
    }
  }

  private void parseNamedResultMap(String namespace, NamedResultMap namedResultMap) {
    validateNamedResultMap(namedResultMap);

    String resultMapId = namespace + '.' + namedResultMap.id();
    Class<?> returnType = namedResultMap.javaType();
    Arg[] args = namedResultMap.constructorArguments();
    Result[] results = namedResultMap.propertyMappings();
    TypeDiscriminator typeDiscriminator = namedResultMap.typeDiscriminator();
    if (AnnotationConstants.NULL_TYPE_DISCRIMINATOR.equals(typeDiscriminator.column())) {
      typeDiscriminator = null;
    }

    applyResultMap(resultMapId, returnType, args, results, typeDiscriminator);
  }

  private void validateNamedResultMap(NamedResultMap namedResultMap) {
    if (!AnnotationConstants.NULL_TYPE_DISCRIMINATOR.equals(namedResultMap.typeDiscriminator().column())) {
      return;
    }

    if (namedResultMap.constructorArguments().length == 0 && namedResultMap.propertyMappings().length == 0) {
      throw new BuilderException("If there is no type discriminator, then the NamedResultMap annotation "
          + "requires at least one constructor argument or property mapping");
    }
  }

  protected String generateResultMapName(String namespace, Method method) {
    Results results = method.getAnnotation(Results.class);
    if (results != null && !results.id().isEmpty()) {
      return namespace + "." + results.id();
    }
    StringBuilder suffix = new StringBuilder();
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    return namespace + "." + method.getName() + suffix;
  }

  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results,
      TypeDiscriminator discriminator) {
    List<ResultMapping> resultMappings = new ArrayList<>();
    applyConstructorArgs(args, returnType, resultMappings, resultMapId);
    applyResults(results, returnType, resultMappings);
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    // TODO add AutoMappingBehaviour
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      for (Case c : discriminator.cases()) {
        List<ResultMapping> resultMappings = new ArrayList<>();
        // issue #136
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings, resultMapId);
        applyResults(c.results(), resultType, resultMappings);
        // TODO add AutoMappingBehaviour
        String caseResultMapId = resultMapId + "-" + c.value();
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
      }
    }
  }

  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      String column = discriminator.column();
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) (discriminator
          .typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<>();
      for (Case c : cases) {
        String value = c.value();
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    return null;
  }

  void parseStatement(Class<?> type, Method method) {
    final Class<?> parameterTypeClass = getParameterType(method);
    final ParamNameResolver paramNameResolver = new ParamNameResolver(type, method,
        configuration.isUseActualParamName());
    final LanguageDriver languageDriver = getLanguageDriver(method);

    final String databaseId = configuration.getDatabaseId();

    getAnnotationWrapper(databaseId, method, true, statementAnnotationTypes).ifPresent(statementAnnotation -> {
      final SqlSource sqlSource = buildSqlSource(statementAnnotation.getAnnotation(), parameterTypeClass,
          paramNameResolver, languageDriver, method);
      final SqlCommandType sqlCommandType = statementAnnotation.getSqlCommandType();
      final Options options = getAnnotationWrapper(databaseId, method, false, Options.class)
          .map(x -> (Options) x.getAnnotation()).orElse(null);
      final ResultOrdered resultOrderedAnnotation = getAnnotationWrapper(databaseId, method, false, ResultOrdered.class)
          .map(x -> (ResultOrdered) x.getAnnotation()).orElse(null);
      final String mappedStatementId = getQualifiedStatementId(namespace, getAndValidateLocalId(method));

      final KeyGenerator keyGenerator;
      String keyProperty = null;
      String keyColumn = null;
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        // first check for SelectKey annotation - that overrides everything else
        SelectKey selectKey = getAnnotationWrapper(databaseId, method, false, SelectKey.class)
            .map(x -> (SelectKey) x.getAnnotation()).orElse(null);
        if (selectKey != null) {
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method),
              paramNameResolver, languageDriver);
          keyProperty = selectKey.keyProperty();
        } else if (options == null) {
          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        } else {
          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
          keyProperty = options.keyProperty();
          keyColumn = options.keyColumn();
        }
      } else {
        keyGenerator = NoKeyGenerator.INSTANCE;
      }

      Integer fetchSize = null;
      Integer timeout = null;
      StatementType statementType = StatementType.PREPARED;
      ResultSetType resultSetType = configuration.getDefaultResultSetType();
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
      boolean flushCache = !isSelect;
      boolean useCache = isSelect;
      if (options != null) {
        if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
          flushCache = true;
        } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
          flushCache = false;
        }
        useCache = options.useCache();
        // issue #348
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null;
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        if (options.resultSetType() != ResultSetType.DEFAULT) {
          resultSetType = options.resultSetType();
        }
      }

      boolean isResultOrdered = false;
      if (resultOrderedAnnotation != null) {
        isResultOrdered = resultOrderedAnnotation.value();
      }

      String resultMapId = null;
      if (isSelect) {
        ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
        if (resultMapAnnotation != null) {
          resultMapId = String.join(",", resultMapAnnotation.value());
        } else {
          resultMapId = generateResultMapName(namespace, method);
        }
      }

      assistant.addMappedStatement(mappedStatementId, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
          // ParameterMapID
          null, parameterTypeClass, resultMapId, getReturnType(method, type), resultSetType, flushCache, useCache,
          // TODO gcode issue #577
          isResultOrdered, keyGenerator, keyProperty, keyColumn, statementAnnotation.getDatabaseId(), languageDriver,
          // ResultSets
          options != null ? nullOrEmpty(options.resultSets()) : null, statementAnnotation.isDirtySelect(),
          paramNameResolver, null, statementAnnotation.getNameMapping());
    });
  }

  private String getAndValidateLocalId(Method method) {
    String localId = getLocalId(method);
    if (localId.isEmpty()) {
      throw new BuilderException(
          String.format("Statement ID cannot be an empty string. Check @StatementId on %s#%s with parameter(s) %s",
              type.getName(), method.getName(), Arrays.toString(method.getParameters())));
    }
    if (localId.indexOf('.') > -1) {
      throw new BuilderException(
          String.format("Dots are not allowed in statement ID. Check @StatementId on %s#%s with parameter(s) %s",
              type.getName(), method.getName(), Arrays.toString(method.getParameters())));
    }
    return localId;
  }

  public static String getLocalId(Method method) {
    StatementId statementIdAnnotation = method.getAnnotation(StatementId.class);
    String methodName = method.getName();
    if (statementIdAnnotation == null) {
      return methodName;
    }
    String annotationValue = statementIdAnnotation.value();
    if (StatementId.APPEND_PARAM_TYPES.equals(annotationValue)) {
      StringBuilder buffer = new StringBuilder(methodName);
      Parameter[] parameters = method.getParameters();
      for (int i = 0; i < parameters.length; i++) {
        buffer.append(i == 0 ? '#' : '_').append(parameters[i].getType().getSimpleName());
      }
      return buffer.toString();
    }
    return annotationValue;
  }

  private LanguageDriver getLanguageDriver(Method method) {
    Lang lang = method.getAnnotation(Lang.class);
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = lang.value();
    }
    return configuration.getLanguageDriver(langClass);
  }

  private Class<?> getParameterType(Method method) {
    Class<?> parameterType = null;
    Parameter[] parameters = method.getParameters();
    for (Parameter param : parameters) {
      Class<?> paramType = param.getType();
      if (RowBounds.class.isAssignableFrom(paramType) || ResultHandler.class.isAssignableFrom(paramType)) {
        continue;
      }
      if (parameterType == null && param.getAnnotation(Param.class) == null) {
        parameterType = paramType;
      } else {
        return ParamMap.class;
      }
    }
    return parameterType;
  }

  private static Class<?> getReturnType(Method method, Class<?> type) {
    Class<?> returnType = method.getReturnType();
    Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
    if (resolvedReturnType instanceof Class) {
      returnType = (Class<?>) resolvedReturnType;
      if (returnType.isArray()) {
        returnType = returnType.getComponentType();
      }
      // gcode issue #508
      if (void.class.equals(returnType)) {
        ResultType rt = method.getAnnotation(ResultType.class);
        if (rt != null) {
          returnType = rt.value();
        }
      }
    } else if (resolvedReturnType instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
      Class<?> rawType = (Class<?>) parameterizedType.getRawType();
      if (isSequentialType(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments.length == 1) {
          Type returnTypeParameter = actualTypeArguments[0];
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue #443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          } else if (returnTypeParameter instanceof GenericArrayType) {
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            // (gcode issue #525) support List<byte[]>
            returnType = Array.newInstance(componentType, 0).getClass();
          }
        }
      } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
        // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments.length == 2) {
          Type returnTypeParameter = actualTypeArguments[1];
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue 443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          }
        }
      } else if (Optional.class.equals(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        Type returnTypeParameter = actualTypeArguments[0];
        if (returnTypeParameter instanceof Class<?>) {
          returnType = (Class<?>) returnTypeParameter;
        }
      }
    }

    return returnType;
  }

  private static boolean isSequentialType(Class<?> type) {
    return Collection.class.isAssignableFrom(type) || Cursor.class.isAssignableFrom(type)
        || Iterator.class.isAssignableFrom(type) || Stream.class.isAssignableFrom(type);
  }

  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Result result : results) {
      int flags = ResultFlag.NONE;
      if (result.id()) {
        flags = ResultFlag.add(flags, ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) (result
          .typeHandler() == UnknownTypeHandler.class ? null : result.typeHandler());
      boolean hasNestedResultMap = hasNestedResultMap(result);
      ResultMapping resultMapping = assistant.buildResultMapping(resultType, nullOrEmpty(result.property()),
          nullOrEmpty(result.column()), result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          hasNestedResultMap ? nestedResultMapId(result) : null, null,
          hasNestedResultMap ? findColumnPrefix(result) : null, typeHandler, flags, null, null, isLazy(result));
      resultMappings.add(resultMapping);
    }
  }

  private String findColumnPrefix(Result result) {
    String columnPrefix = result.one().columnPrefix();
    if (columnPrefix.isEmpty()) {
      columnPrefix = result.many().columnPrefix();
    }
    return columnPrefix;
  }

  private String nestedResultMapId(Result result) {
    String resultMapId = result.one().resultMap();
    if (resultMapId.isEmpty()) {
      resultMapId = result.many().resultMap();
    }
    if (!resultMapId.contains(".")) {
      resultMapId = namespace + "." + resultMapId;
    }
    return resultMapId;
  }

  private boolean hasNestedResultMap(Result result) {
    if (!result.one().resultMap().isEmpty() && !result.many().resultMap().isEmpty()) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return !result.one().resultMap().isEmpty() || !result.many().resultMap().isEmpty();
  }

  private String nestedSelectId(Result result) {
    String nestedSelect = result.one().select();
    if (nestedSelect.isEmpty()) {
      nestedSelect = result.many().select();
    }
    if (!nestedSelect.contains(".")) {
      nestedSelect = namespace + "." + nestedSelect;
    }
    return nestedSelect;
  }

  private boolean isLazy(Result result) {
    boolean isLazy = configuration.isLazyLoadingEnabled();
    if (!result.one().select().isEmpty() && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = result.one().fetchType() == FetchType.LAZY;
    } else if (!result.many().select().isEmpty() && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = result.many().fetchType() == FetchType.LAZY;
    }
    return isLazy;
  }

  private boolean hasNestedSelect(Result result) {
    if (!result.one().select().isEmpty() && !result.many().select().isEmpty()) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return !result.one().select().isEmpty() || !result.many().select().isEmpty();
  }

  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings,
      String resultMapId) {
    final List<ResultMapping> mappings = new ArrayList<>();
    for (Arg arg : args) {
      int flags = ResultFlag.CONSTRUCTOR.mask();
      if (arg.id()) {
        flags = ResultFlag.add(flags, ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) (arg
          .typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
      ResultMapping resultMapping = assistant.buildResultMapping(resultType, nullOrEmpty(arg.name()),
          nullOrEmpty(arg.column()), arg.javaType() == void.class ? null : arg.javaType(),
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(), nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()), null, nullOrEmpty(arg.columnPrefix()), typeHandler, flags, null, null, false);
      mappings.add(resultMapping);
    }

    final ResultMappingConstructorResolver resolver = new ResultMappingConstructorResolver(configuration, mappings,
        resultType, resultMapId);
    resultMappings.addAll(resolver.resolveWithConstructor());
  }

  private String nullOrEmpty(String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }

  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId,
      Class<?> parameterTypeClass, ParamNameResolver paramNameResolver, LanguageDriver languageDriver) {
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    // defaults
    String databaseId = selectKeyAnnotation.databaseId().isEmpty() ? null : selectKeyAnnotation.databaseId();

    SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass,
        paramNameResolver, languageDriver);

    assistant.addSelectKeyStatement(id, sqlSource, statementType, parameterTypeClass, resultTypeClass, keyProperty,
        keyColumn, databaseId, languageDriver, paramNameResolver);

    id = assistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

  private SqlSource buildSqlSource(Annotation annotation, Class<?> parameterType, ParamNameResolver paramNameResolver,
      LanguageDriver languageDriver, Method method) {
    if (annotation instanceof Select) {
      return buildSqlSourceFromStrings(((Select) annotation).value(), parameterType, paramNameResolver, languageDriver);
    } else if (annotation instanceof Update) {
      return buildSqlSourceFromStrings(((Update) annotation).value(), parameterType, paramNameResolver, languageDriver);
    } else if (annotation instanceof Insert) {
      return buildSqlSourceFromStrings(((Insert) annotation).value(), parameterType, paramNameResolver, languageDriver);
    } else if (annotation instanceof Delete) {
      return buildSqlSourceFromStrings(((Delete) annotation).value(), parameterType, paramNameResolver, languageDriver);
    } else if (annotation instanceof SelectKey) {
      return buildSqlSourceFromStrings(((SelectKey) annotation).statement(), parameterType, paramNameResolver,
          languageDriver);
    }

    SqlProvider sqlProvider = new AnnotationSqlProvider(configuration, annotation, type, method);
    Lang lang = method == null ? null : method.getAnnotation(Lang.class);
    return new ProviderSqlSource(lang == null ? null : lang.value(), type, method, sqlProvider);
  }

  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass,
      ParamNameResolver paramNameResolver, LanguageDriver languageDriver) {
    return languageDriver.createSqlSource(configuration, String.join(" ", strings).trim(), parameterTypeClass,
        paramNameResolver);
  }

  @SafeVarargs
  private Optional<AnnotationWrapper> getAnnotationWrapper(String databaseId, Method method, boolean errorIfNoMatch,
      Class<? extends Annotation>... targetTypes) {
    return getAnnotationWrapper(databaseId, method, errorIfNoMatch, Arrays.asList(targetTypes));
  }

  private Optional<AnnotationWrapper> getAnnotationWrapper(String databaseId, Method method, boolean errorIfNoMatch,
      Collection<Class<? extends Annotation>> targetTypes) {
    Map<String, AnnotationWrapper> statementAnnotations = targetTypes.stream()
        .flatMap(x -> Arrays.stream(method.getAnnotationsByType(x))).map(AnnotationWrapper::new)
        .collect(Collectors.toMap(AnnotationWrapper::getDatabaseId, x -> x, (existing, duplicate) -> {
          throw new BuilderException(
              String.format("Detected conflicting annotations '%s' and '%s' on '%s'.", existing.getAnnotation(),
                  duplicate.getAnnotation(), method.getDeclaringClass().getName() + "." + method.getName()));
        }));
    AnnotationWrapper annotationWrapper = null;
    if (databaseId != null) {
      annotationWrapper = statementAnnotations.get(databaseId);
    }
    if (annotationWrapper == null) {
      annotationWrapper = statementAnnotations.get("");
    }
    if (errorIfNoMatch && annotationWrapper == null && !statementAnnotations.isEmpty()) {
      // Annotations exist, but there is no matching one for the specified databaseId
      throw new BuilderException(String.format(
          "Could not find a statement annotation that correspond a current database or default statement on method '%s.%s'. Current database id is [%s].",
          method.getDeclaringClass().getName(), method.getName(), databaseId));
    }
    return Optional.ofNullable(annotationWrapper);
  }

  public static Class<?> getMethodReturnType(String mapperFqn, String localStatementId) {
    if (mapperFqn == null || localStatementId == null) {
      return null;
    }
    // No corresponding mapper interface which is OK
    Class<?> mapperClass = Resources.classForNameOrElseNull(mapperFqn);
    if (mapperClass == null) {
      return null;
    }
    for (Method method : mapperClass.getMethods()) {
      if (method.getName().equals(localStatementId) && canHaveStatement(method)) {
        return getReturnType(method, mapperClass);
      }
    }
    return null;
  }

  private static class AnnotationWrapper {
    private final Annotation annotation;
    private final String databaseId;
    private final SqlCommandType sqlCommandType;
    private NameMapping nameMapping;
    private boolean dirtySelect;

    AnnotationWrapper(Annotation annotation) {
      this.annotation = annotation;
      if (annotation instanceof Select) {
        databaseId = ((Select) annotation).databaseId();
        sqlCommandType = SqlCommandType.SELECT;
        dirtySelect = ((Select) annotation).affectData();
        nameMapping = ((Select) annotation).namingStrategy();
      } else if (annotation instanceof Update) {
        databaseId = ((Update) annotation).databaseId();
        sqlCommandType = SqlCommandType.UPDATE;
      } else if (annotation instanceof Insert) {
        databaseId = ((Insert) annotation).databaseId();
        sqlCommandType = SqlCommandType.INSERT;
      } else if (annotation instanceof Delete) {
        databaseId = ((Delete) annotation).databaseId();
        sqlCommandType = SqlCommandType.DELETE;
      } else if (annotation instanceof SelectProvider) {
        databaseId = ((SelectProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.SELECT;
        dirtySelect = ((SelectProvider) annotation).affectData();
        nameMapping = ((SelectProvider) annotation).namingStrategy();
      } else if (annotation instanceof UpdateProvider) {
        databaseId = ((UpdateProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.UPDATE;
      } else if (annotation instanceof InsertProvider) {
        databaseId = ((InsertProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.INSERT;
      } else if (annotation instanceof DeleteProvider) {
        databaseId = ((DeleteProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.DELETE;
      } else {
        sqlCommandType = SqlCommandType.UNKNOWN;
        if (annotation instanceof Options) {
          databaseId = ((Options) annotation).databaseId();
        } else if (annotation instanceof SelectKey) {
          databaseId = ((SelectKey) annotation).databaseId();
        } else {
          databaseId = "";
        }
      }
    }

    Annotation getAnnotation() {
      return annotation;
    }

    SqlCommandType getSqlCommandType() {
      return sqlCommandType;
    }

    String getDatabaseId() {
      return databaseId;
    }

    boolean isDirtySelect() {
      return dirtySelect;
    }

    NameMapping getNameMapping() {
      return nameMapping == NameMapping.NONE ? null : nameMapping;
    }
  }

  public static String getQualifiedStatementId(Method method) {
    return getQualifiedStatementId(method.getDeclaringClass(), method);
  }

  public static String getQualifiedStatementId(Class<?> mapperClass, Method method) {
    return getQualifiedStatementId(getNamespace(mapperClass), getLocalId(method));
  }

  public static String getQualifiedStatementId(Class<?> mapperClass, String statementName) {
    return getQualifiedStatementId(getNamespace(mapperClass), statementName);
  }

  public static String getQualifiedStatementId(String namespace, String statementName) {
    return namespace + "." + statementName;
  }

  public static <T, U, R> String getQualifiedStatementId(ThrowableBiFunction<T, U, R> methodRef) {
    Objects.requireNonNull(methodRef, "method ref is null");
    SerializedLambda lambda = LambdaUtils.getSerializedLambda(methodRef);
    String className = ClassUtils.convertResourcePathToClassName(lambda.getImplClass());
    String methodName = lambda.getImplMethodName();
    return getQualifiedStatementId(className, methodName);
  }
}

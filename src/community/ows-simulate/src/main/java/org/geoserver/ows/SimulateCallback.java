/* 
 * (c) 2017 Open Source Geospatial Foundation - all rights reserved
 */
package org.geoserver.ows;

import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import net.sf.json.util.JSONStringer;
import org.apache.commons.lang.ClassUtils;
import org.eclipse.emf.ecore.EObject;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.Info;
import org.geoserver.ows.kvp.FormatOptionsKvpParser;
import org.geoserver.ows.util.ClassProperties;
import org.geoserver.ows.util.OwsUtils;
import org.geoserver.platform.Operation;
import org.geoserver.platform.Service;
import org.geoserver.platform.ServiceException;
import org.geotools.data.FeatureSource;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.util.Converters;
import org.opengis.coverage.grid.GridCoverageReader;
import org.opengis.filter.Filter;
import org.opengis.geometry.Envelope;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dispatcher callback that adds an option to "simulate" requests handled by the 
 * OWS dispatcher. 
 * <p>
 *   In simulation mode the resulting request object is sent back as the response
 *   to the request.
 * </p>
 */
public class SimulateCallback implements DispatcherCallback {

  public static final String KVP = "simulate";
  public static final String OPT_DEPTH = "depth";
  
  static Converter<String,String> KEY_CONVERTER = CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_UNDERSCORE);

  @Override
  public Request init(Request request) {
    return request;
  }

  @Override
  public Service serviceDispatched(Request request, Service service) throws ServiceException {
    return null;
  }

  @Override
  public Operation operationDispatched(Request request, Operation operation) {
    String sim = Optional.ofNullable(request.getRawKvp().get(KVP)).map(String.class::cast).orElse(null);
    if (sim == null) {
      return operation;
    }

    Map<String,Object> simOpts;
    if (sim.contains(";")) {
      try {
        simOpts = (Map<String, Object>) new FormatOptionsKvpParser().parse(sim.toString());
      } catch (Exception e) {
        throw new RuntimeException("Illegal syntax for simulate options: simulate=<key>:<val>[;<key>:<val>]*", e);
      }
    }
    else {
      simOpts = Collections.emptyMap();
    }

    throw new HttpErrorCodeException(202, toJSON(operation, simOpts));
  }

  @Override
  public Object operationExecuted(Request request, Operation operation, Object result) {
    return result;
  }

  @Override
  public Response responseDispatched(Request request, Operation operation, Object result, Response response) {
    return response;
  }

  @Override
  public void finished(Request request) {

  }

  String toJSON(Operation op, Map<String,Object> opts) {
    int depth = Converters.convert(opts.getOrDefault(OPT_DEPTH, 3), Integer.class);

    JSONStringer out = new JSONStringer();
    out.object();
    
    Service srv = op.getService();
    out.key("service").object()
       .key("name").value(srv.getId())
       .key("version").value(srv.getVersion())
       .endObject();

    out.key("operation").object()
       .key("name").value(op.getId());

    Object req = Arrays.stream(op.getParameters()).findFirst().orElse(null);
    if (req != null) {
      out.key("request");
      traverse(req, 0, depth, out);
    }

    out.endObject(); // operation

    out.endObject();
    return out.toString();
  }

  void traverse(Object obj, int depth, int maxDepth, JSONStringer out) {
    if (obj == null) {
      out.value(null);
      return;
    }
    if (obj instanceof Envelope) {
      Envelope e = (Envelope) obj;
      out.object();
      out.key("x1").value(e.getMinimum(0));
      out.key("y1").value(e.getMaximum(0));

      if (e.getDimension() > 1) {
        out.key("x2").value(e.getMinimum(1));
        out.key("y2").value(e.getMaximum(1));
      }
      out.endObject();
      return;
    }
    if (obj instanceof com.vividsolutions.jts.geom.Envelope) {
      com.vividsolutions.jts.geom.Envelope e = (com.vividsolutions.jts.geom.Envelope) obj;
      out.object()
        .key("x1").value(e.getMinX())
        .key("y1").value(e.getMinY())
        .key("x2").value(e.getMaxX())
        .key("y2").value(e.getMaxY())
        .endObject();
      return;
    }
    if (obj instanceof Filter) {
      out.value(CQL.toCQL((Filter)obj));
      return;
    }
    if (!Modifier.isPublic(obj.getClass().getModifiers())) {
      out.value(obj.toString());
      return;
    }

    String className = obj.getClass().getName(); 
    if (className.startsWith("java.") || className.startsWith("org.geotools.") || className.startsWith("org.opengis.")) {
      if (OwsUtils.has(obj, "name")) {
        out.value(OwsUtils.get(obj, "name"));
      }
      else {
        out.value(obj.toString());
      }
      return;
    }
    out.object();

    propsOf(obj)
        .filter(p -> !isMetadata(p.name))    // skip class metadata, etc...
        .filter(p ->                         // skip geotools data objects
          !(FeatureSource.class.isAssignableFrom(p.type) || GridCoverageReader.class.isAssignableFrom(p.type))
        )
        .filter(p ->                         // skip geoserver catalog objects
          !(FeatureTypeInfo.class.isAssignableFrom(p.type) || CoverageInfo.class.isAssignableFrom(p.type))
        )
        .filter(p -> !isEmpty(p.value()))
        .forEach(p -> {
          out.key(toKey(p));

          Object value = p.value();
          if (isPrimitive(value) || depth >= maxDepth) {
            out.value(value);
          }
          else if (value instanceof Info) {
            out.value(((Info)value).getId());
          }
          else if (value instanceof Collection) {
            out.array();
            for (Object o : ((Collection)value)) {
              traverse(o, depth+1, maxDepth, out);
            }
            out.endArray();
          }
          else {
            traverse(value, depth+1, maxDepth, out);
          }
        });

    out.endObject();
  }

  boolean isMetadata(String f) {
    if ("Class".equalsIgnoreCase(f)) return true;
    if ("DeclaringClass".equalsIgnoreCase(f)) return true;
    if ("EStructuralFeature".equalsIgnoreCase(f)) return true;
    return false;
  }
  
  boolean isEmpty(Object value) {
    // skip empty properties
    if(value == null || (value instanceof Collection && ((Collection) value).isEmpty())
        || (value instanceof Map && ((Map) value).isEmpty())) {
      return true;
    }

    return false;
  }
  
  boolean isPrimitive(Object value) {
    if (value instanceof String) {
      return true;
    }
    if (value instanceof Number) {
      return true;
    }
    Class clazz = value.getClass();
    return clazz.isPrimitive() || ClassUtils.wrapperToPrimitive(clazz) != null;
  }

  static Pattern UPPER_REGEX = Pattern.compile("([A-Z])([A-Z]+)");
  
  String toKey(Property p) {
    String key = p.name;
    Matcher m = UPPER_REGEX.matcher(key);

    while(m.find()) {
      key = m.replaceFirst(m.group(1) + m.group(2).toLowerCase());
      m = UPPER_REGEX.matcher(key);
    }

    return KEY_CONVERTER.convert(key);
  }

  final class Property {
    final String name;
    final Class type;
    private final Supplier<Object> value;
    Object v;

    Property(String name, Class type, Supplier<Object> value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }

    Object value() {
      if (v == null) {
        v = value.get();
      }
      return v;
    }
  }

  Stream<Property> propsOf(Object obj) {
    if (obj instanceof EObject) {
      EObject eobj = (EObject) obj;
      return eobj.eClass().getEAllStructuralFeatures().stream()
          .map(f -> new Property(f.getName(), f.getEType().getInstanceClass(), () -> eobj.eGet(f)));
    }
    else {
      ClassProperties classProps = OwsUtils.getClassProperties(obj.getClass());
      return classProps.properties().stream()
          .map(p -> new Property(p, classProps.getter(p, null).getReturnType(), () -> {
              Object value;
              try {
                value = OwsUtils.get(obj, p);
              }
              catch(Exception e) {
                //value = Throwables.getStackTraceAsString(e);
                value = e.getMessage();
              }
              return value;
            }));
    }
  }
}
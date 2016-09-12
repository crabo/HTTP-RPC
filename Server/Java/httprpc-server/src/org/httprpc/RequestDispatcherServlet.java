/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.httprpc;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.security.Principal;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.httprpc.template.TemplateEncoder;

/**
 * Servlet that dispatches HTTP-RPC web service requests.
 */
@MultipartConfig
public class RequestDispatcherServlet extends HttpServlet {
    private static final long serialVersionUID = 0;

    // Resource structure
    private static class Resource {
    	public final HashMap<Method, String[]> methodParamNameMap = new HashMap<>();//by crabo
        public final HashMap<String, LinkedList<Method>> handlerMap = new HashMap<>();
        public final HashMap<String, Resource> resources = new HashMap<>();
    }

    // User role set
    private static class UserRoleSet extends AbstractSet<String> {
        private HttpServletRequest request;

        public UserRoleSet(HttpServletRequest request) {
            this.request = request;
        }

        @Override
        public boolean contains(Object object) {
            return request.isUserInRole(object.toString());
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<String> iterator() {
            throw new UnsupportedOperationException();
        }
    }

    private Class<?> serviceType = null;
    private Resource root = null;
    private String serviceClassName;//by crabo
	public void setServiceClassName(String className){
		serviceClassName=className;
	}
	private String packageName;//by crabo
	public void setPackageName(String packageName){
		this.packageName=packageName;
	}
	public String getRoutePath(String path,String methodName){
		if(path.length() == 0){
			return packageName+"/"+methodName;
		}
		
		if(path.indexOf("/")<0)
			path="/"+path;
		
		return packageName+path;
	}

    private static final String UTF_8_ENCODING = "UTF-8";

    private static final String MULTIPART_FORM_DATA_MIME_TYPE = "multipart/form-data";

    private static final String USER_AGENT_KEY = "User-Agent";

    @Override
    public void init() throws ServletException {
        // Load service class
    	if(serviceClassName==null)//by crabo
        	serviceClassName = getServletConfig().getInitParameter("serviceClassName");

        try {
            serviceType = Class.forName(serviceClassName);
        } catch (ClassNotFoundException exception) {
            throw new ServletException(exception);
        }

        if (!WebService.class.isAssignableFrom(serviceType)) {
            throw new ServletException("Invalid service type.");
        }

        // Populate resource tree
        root = new Resource();

        Method[] methods = serviceType.getMethods();

        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];

            RPC rpc = method.getAnnotation(RPC.class);

            if (rpc != null) {
                Resource resource = root;
                
                //by crabo: //rpc.path().split("/");
                String[] components = getRoutePath(rpc.path(),method.getName()).split("/");

                for (int j = 0; j < components.length; j++) {
                    String component = components[j];

                    if (component.length() == 0) {
                        continue;
                    }

                    Resource child = resource.resources.get(component);

                    if (child == null) {
                        child = new Resource();

                        resource.resources.put(component, child);
                    }

                    resource = child;
                }

                String key = rpc.method().toLowerCase();

                LinkedList<Method> handlerList = resource.handlerMap.get(key);

                if (handlerList == null) {
                    handlerList = new LinkedList<>();

                    resource.handlerMap.put(key, handlerList);
                }

                handlerList.add(method);
                
               //added by crabo
                if(rpc.args().length>0){
                	if(rpc.args().length!=method.getParameterTypes().length)
                		throw new IllegalArgumentException("Wrong 'args' name list in @RPC: "+serviceClassName+"."+method.getName());
                	
                	resource.methodParamNameMap.put(method,rpc.args());
                }else if(method.getParameterTypes().length>0)
                {
                	//不设置，通过编译器-Paramertes 保留方法的参数名！！ 否则是 arg0,arg1....的参数名
                	Parameter[] ps = method.getParameters();
                	String[] names = new String[ps.length];
                	
                	for(int k=0;k<ps.length;k++)
                		names[k]=ps[k].getName();
                	
                	resource.methodParamNameMap.put(method,names);
                }
            }
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Look up resource
        String pathInfo = request.getPathInfo();
        String extension = null;

        Resource resource = root;

        if (pathInfo == null) {
            String servletPath = request.getServletPath();

            int j = servletPath.lastIndexOf('.');

            if (j != -1) {
                extension = servletPath.substring(j);
            }
        } else {
            String[] components = pathInfo.split("/");

            for (int i = 0; i < components.length; i++) {
                String component = components[i];

                if (component.length() == 0) {
                    continue;
                }

                Resource child = resource.resources.get(component);

                if (child == null && i == components.length - 1) {
                    int j = component.lastIndexOf('.');

                    if (j != -1) {
                        child = resource.resources.get(component.substring(0, j));

                        if (child != null) {
                            extension = component.substring(j);
                        }
                    }
                }

                if (child == null) {
                    break;
                }

                resource = child;
            }
        }

        if (resource == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().append("ROUTE NOT FOUND");
            return;
        }

        LinkedList<Method> handlerList = resource.handlerMap.get(request.getMethod().toLowerCase());

        if (handlerList == null) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.getWriter().append("ROUTE METHOD NOT VALID");
            return;
        }

        // Set character encoding
        if (request.getCharacterEncoding() == null) {
            request.setCharacterEncoding(UTF_8_ENCODING);
        }

        // Populate parameter map
        HashMap<String, String[]> parameterMap = new HashMap<>();

        Enumeration<String> parameterNames = request.getParameterNames();

        while (parameterNames.hasMoreElements()) {
            String name = parameterNames.nextElement();
            String[] values = request.getParameterValues(name);

            /*LinkedList<String> valueList = new LinkedList<>();

            for (int i = 0; i < values.length; i++) {
                valueList.add(values[i]);
            }*/

            parameterMap.put(name, values);
        }

        // Populate file map
        HashMap<String, LinkedList<File>> fileMap = new HashMap<>();

        String contentType = request.getContentType();

        if (contentType != null && contentType.startsWith(MULTIPART_FORM_DATA_MIME_TYPE)) {
            for (Part part : request.getParts()) {
                String submittedFileName = part.getSubmittedFileName();

                if (submittedFileName == null || submittedFileName.length() == 0) {
                    continue;
                }

                String name = part.getName();

                LinkedList<File> fileList = fileMap.get(name);

                if (fileList == null) {
                    fileList = new LinkedList<>();
                    fileMap.put(name, fileList);
                }

                File file = File.createTempFile(part.getName(), "_" + part.getSubmittedFileName());
                part.write(file.getAbsolutePath());

                fileList.add(file);
            }
        }

        // Invoke handler method
        Method method = getMethod(resource,handlerList, parameterMap, fileMap);

        if (method == null) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.getWriter().append("API METHOD NOT FOUND");
            return;
        }

        Class<?> returnType = method.getReturnType();

        Encoder encoder = null;

        if (returnType != Void.TYPE && returnType != Void.class) {
            if (extension != null) {
                String userAgent = request.getHeader(USER_AGENT_KEY);

                if (userAgent != null) {
                    String mimeType = getServletContext().getMimeType(extension);

                    Template[] templates = method.getAnnotationsByType(Template.class);

                    for (int i = 0; i < templates.length; i++) {
                        Template template = templates[i];

                        if (template.contentType().equals(mimeType) && userAgent.matches(template.userAgent())) {
                            encoder = new TemplateEncoder(serviceType.getResource(template.name()), mimeType, serviceType.getName());

                            Map<String, Object> context = ((TemplateEncoder)encoder).getContext();

                            context.put("scheme", request.getScheme());
                            context.put("serverName", request.getServerName());
                            context.put("serverPort", request.getServerPort());
                            context.put("contextPath", request.getContextPath());

                            break;
                        }
                    }
                }

                if (encoder == null) {
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                    response.getWriter().append("API NOT ACCEPTABLE");
                    return;
                }
            } else {
                Encoding encoding = method.getAnnotation(Encoding.class);

                if (encoding != null) {
                    try {
                        encoder = encoding.value().newInstance();
                    } catch (InstantiationException | IllegalAccessException exception) {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

                        request.getServletContext().log(getClass().getName(), exception);
                        response.getWriter().append("API ENCODER ERROR");
                        return;
                    }
                } else {
                    encoder = new JSONEncoder();
                }
            }
        }

        try {
            Object result;
            try {
                WebService service;
                if (!Modifier.isStatic(method.getModifiers())) {
                    service = (WebService)serviceType.newInstance();

                    service.setLocale(request.getLocale());

                    Principal userPrincipal = request.getUserPrincipal();

                    if (userPrincipal != null) {
                        service.setUserName(userPrincipal.getName());
                        service.setUserRoles(new UserRoleSet(request));
                    }
                } else {
                    service = null;
                }
                if(beforeInvoke(method,service,request,response)==false)//by crabo
                	return;
                result = method.invoke(service, getArguments(resource,request,method, parameterMap, fileMap));
            } catch (Exception exception) {
            	exception.printStackTrace();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

                Throwable cause = exception.getCause();

                if (cause != null) {
                    request.getServletContext().log(getClass().getName(), cause);
                }

                return;
            }

            // Write response
            if (returnType == Void.TYPE || returnType == Void.class) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } else {
                response.setContentType(encoder.getContentType(result));

                try {
                    encoder.writeValue(result, response.getOutputStream());
                } catch (IOException exception) {
                	exception.printStackTrace();
                    request.getServletContext().log(getClass().getName(), exception);
                }
            }
        }catch (Exception exception) {
        	exception.printStackTrace();
        	response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        	exception.printStackTrace(response.getWriter());
        }
        finally {
            // Delete files
            for (LinkedList<File> fileList : fileMap.values()) {
                for (File file : fileList) {
                    file.delete();
                }
            }
        }
    }
    protected boolean beforeInvoke(Method method,WebService target,HttpServletRequest request, HttpServletResponse response)
    		throws IOException{
    	return true;
    }

    private static Method getMethod(Resource resource,LinkedList<Method> handlerList, HashMap<String, String[]> parameterMap,
        HashMap<String, LinkedList<File>> fileMap) {
        Method method = null;

        int n = parameterMap.size() + fileMap.size();

        int i = Integer.MAX_VALUE;

        if(handlerList.size()==1)//added by crabo
        	return handlerList.getFirst();
        
        for (Method handler : handlerList) {
        	//by crabo
        	String[] parameterNames = resource.methodParamNameMap.get(handler);
            //Parameter[] parameters = handler.getParameters();

            if (parameterNames.length >= n) {
                int j = 0;

                for (int k = 0; k < parameterNames.length; k++) {
                    String name = parameterNames[k];//.getName();

                    if (!(parameterMap.containsKey(name) || fileMap.containsKey(name))) {
                        j++;
                    }
                }

                if (parameterNames.length - j == n && j < i) {
                    method = handler;

                    i = j;
                }
            }
        }

        return method;
    }

    private static Object[] getArguments(Resource resource,HttpServletRequest request,Method method, HashMap<String, String[]> parameterMap,
        HashMap<String, LinkedList<File>> fileMap) throws IOException {
    	//by crabo
    	//Parameter[] parameters = method.getParameters();
        Class<?>[] parameters = method.getParameterTypes();
    	String[] parameterNames = resource.methodParamNameMap.get(method);
    	
        Object[] arguments = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            //Parameter parameter = parameters[i];

            String name = parameterNames[i];//parameter.getName();
            Class<?> type = parameters[i];

            Object argument;
            if (type == List.class) {
                //ParameterizedType parameterizedType = (ParameterizedType)parameter.getParameterizedType();
            	ParameterizedType parameterizedType=(ParameterizedType)type.getGenericSuperclass();
            	Type elementType = parameterizedType.getActualTypeArguments()[0];

                List<Object> list;
                if (elementType == URL.class) {
                    LinkedList<File> fileList = fileMap.get(name);

                    if (fileList != null) {
                        list = new ArrayList<>(fileList.size());

                        for (File file : fileList) {
                            list.add(file.toURI().toURL());
                        }
                    } else {
                        list = Collections.emptyList();
                    }
                } else {
                	String[] valueList = parameterMap.get(name);

                    if (valueList != null) {
                        int n = valueList.length;//.size();

                        list = new ArrayList<>(n);

                        for (String value : valueList) {
                            list.add(getArgument(value, elementType));
                        }
                    } else {
                        list = Collections.emptyList();
                    }
                }

                argument = list;
            } else if (type == URL.class) {
                LinkedList<File> fileList = fileMap.get(name);

                if (fileList != null) {
                    argument = fileList.getFirst().toURI().toURL();
                } else {
                    argument = null;
                }
            } else if (type==HttpServletRequest.class){
            	argument=request;
            }else {
            	String[] values = parameterMap.get(name);
            	String value=values==null?null:values[0];
            	/*
                LinkedList<String> valueList = parameterMap.get(name);

                String value;
                if (valueList != null) {
                    value = valueList.getFirst();
                } else {
                    value = null;
                }*/

                argument = getArgument(value, type);
            }

            arguments[i] = argument;
        }

        return arguments;
    }

    private static Object getArgument(String value, Type type) {
        Object argument;
        if (type == String.class) {
            argument = value;
        } else if (type == Byte.TYPE) {
            argument = (value == null) ? 0 : Byte.parseByte(value);
        } else if (type == Byte.class) {
            argument = (value == null) ? null : Byte.parseByte(value);
        } else if (type == Short.TYPE) {
            argument = (value == null) ? 0 : Short.parseShort(value);
        } else if (type == Short.class) {
            argument = (value == null) ? null : Short.parseShort(value);
        } else if (type == Integer.TYPE) {
            argument = (value == null) ? 0 : Integer.parseInt(value);
        } else if (type == Integer.class) {
            argument = (value == null) ? null : Integer.parseInt(value);
        } else if (type == Long.TYPE) {
            argument = (value == null) ? 0 : Long.parseLong(value);
        } else if (type == Long.class) {
            argument = (value == null) ? null : Long.parseLong(value);
        } else if (type == Float.TYPE) {
            argument = (value == null) ? 0 : Float.parseFloat(value);
        } else if (type == Float.class) {
            argument = (value == null) ? null : Float.parseFloat(value);
        } else if (type == Double.TYPE) {
            argument = (value == null) ? 0 : Double.parseDouble(value);
        } else if (type == Double.class) {
            argument = (value == null) ? null : Double.parseDouble(value);
        } else if (type == Boolean.TYPE) {
            argument = (value == null) ? false : Boolean.parseBoolean(value);
        } else if (type == Boolean.class) {
            argument = (value == null) ? null : Boolean.parseBoolean(value);
        } else {
            throw new UnsupportedOperationException("Invalid parameter type.");
        }

        return argument;
    }
}

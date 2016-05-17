/*
 * Copyright 2013 (c) MuleSoft, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.raml.v2.internal.impl.commons.model.builder;

import static org.raml.v2.internal.impl.commons.model.builder.ModelUtils.isPrimitiveOrWrapperOrString;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.raml.v2.internal.framework.nodes.ArrayNode;
import org.raml.v2.internal.framework.nodes.Node;
import org.raml.v2.internal.framework.nodes.NullNode;
import org.raml.v2.internal.framework.nodes.ObjectNode;
import org.raml.v2.internal.framework.nodes.SimpleTypeNode;
import org.raml.v2.internal.impl.commons.model.Api;
import org.raml.v2.internal.impl.commons.model.BaseModelElement;
import org.raml.v2.internal.impl.commons.model.DefaultModelElement;
import org.raml.v2.internal.impl.commons.model.StringType;
import org.raml.v2.internal.impl.commons.nodes.RamlDocumentNode;
import org.raml.v2.internal.utils.NodeSelector;
import org.raml.v2.internal.utils.SimpleValueTransformer;

public class ModelProxyBuilder
{

    public static <T> T createRaml(Class<T> apiInterface, RamlDocumentNode delegateNode)
    {
        return (T) Proxy.newProxyInstance(
                apiInterface.getClassLoader(),
                new Class[] {apiInterface},
                new SimpleProxy(new Api(delegateNode)));
    }

    private static class SimpleProxy implements InvocationHandler
    {

        private Object delegate;

        public SimpleProxy(Object delegate)
        {
            if (delegate == null)
            {
                throw new IllegalArgumentException("delegate cannot be null");
            }
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            final Class<?> returnType = method.getReturnType();
            final Type genericReturnType = method.getGenericReturnType();
            final Method delegateMethod = findMatchingMethod(method);
            try
            {
                if (delegateMethod == null)
                {
                    return fromNodeKey(method, genericReturnType);
                }
                else
                {
                    return fromMethod(args, returnType, genericReturnType, delegateMethod);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException("Internal error while trying to call " + method.toGenericString(), e);
            }
        }

        protected Object fromNodeKey(Method method, Type genericReturnType)
        {
            final String propertyName = method.getName();
            if (delegate instanceof BaseModelElement && method.getParameterTypes().length == 0)
            {
                return resolveValue(genericReturnType, NodeSelector.selectFrom(propertyName, ((BaseModelElement) delegate).getNode()));
            }
            else
            {
                throw new RuntimeException("Can not resolve method : " + method.toGenericString() + " on " + delegate.getClass().getName());
            }
        }

        protected Object fromMethod(Object[] args, Class<?> returnType, Type genericReturnType, Method delegateMethod) throws IllegalAccessException, InvocationTargetException
        {
            if (isPrimitiveOrWrapperOrString(returnType) || isObject(returnType))
            {
                return delegateMethod.invoke(delegate, args);
            }

            // antother spec interface
            if (!(genericReturnType instanceof ParameterizedType))
            {
                Object result = delegateMethod.invoke(delegate, args);
                if (result == null)
                {
                    return null;
                }
                // interface expects single element or list depending on version
                // e.g.:
                // List<MimeType> mediaType(); //v10
                // MimeType mediaType(); //v08
                if (result instanceof List)
                {
                    if (((List) result).isEmpty())
                    {
                        return null;
                    }
                    result = ((List) result).get(0);
                }
                return Proxy.newProxyInstance(returnType.getClassLoader(), new Class[] {returnType}, new SimpleProxy(result));
            }

            // list
            if (List.class.isAssignableFrom(returnType))
            {
                final List<Object> returnList = new ArrayList<>();
                final List<?> result = (List<?>) delegateMethod.invoke(delegate, args);
                final Class<?> itemClass = (Class<?>) ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
                if (result != null)
                {
                    if (isPrimitiveOrWrapperOrString(itemClass))
                    {
                        return result;
                    }
                    for (Object item : result)
                    {
                        returnList.add(Proxy.newProxyInstance(itemClass.getClassLoader(), new Class[] {itemClass}, new SimpleProxy(item)));
                    }
                }
                return returnList;
            }

            throw new RuntimeException("case not handled yet... " + returnType.getName());
        }

        protected Object resolveValue(Type returnType, Node node)
        {
            final SimpleValueTransformer[] values = SimpleValueTransformer.values();
            final Class<?> returnClass = toClass(returnType);
            for (SimpleValueTransformer value : values)
            {
                if (value.accepts(returnClass))
                {
                    return value.adaptTo(node, returnClass);
                }
            }

            // If it is not a simple type then it can be a list or an pojo
            if (List.class.isAssignableFrom(returnClass) && returnType instanceof ParameterizedType)
            {
                final Type itemClass = ((ParameterizedType) returnType).getActualTypeArguments()[0];
                final List<Object> returnList = new ArrayList<>();
                if (node == null)
                {
                    return returnList;
                }
                else if (node instanceof ArrayNode || node instanceof ObjectNode)
                {
                    final List<Node> children = node.getChildren();
                    for (Node child : children)
                    {
                        returnList.add(resolveValue(itemClass, child));
                    }
                }
                else
                {
                    returnList.add(resolveValue(itemClass, node));
                }
                return returnList;
            }
            else if (returnClass.equals(Object.class))
            {

                if (node instanceof SimpleTypeNode)
                {
                    return ((SimpleTypeNode) node).getValue();
                }
                else
                {
                    // TODO: What to do here
                    return null;
                }
            }
            else
            {
                if (node == null || node instanceof NullNode)
                {
                    return null;
                }

                final String simpleName = returnClass.getSimpleName();
                Object delegate;
                try
                {
                    final Class<?> aClass = Class.forName("org.raml.v2.internal.impl.commons.model." + simpleName);
                    delegate = aClass.getConstructor(Node.class).newInstance(node);
                }
                catch (Exception e)
                {
                    if (node instanceof SimpleTypeNode)
                    {
                        delegate = new StringType(node);
                    }
                    else
                    {
                        // No specific class for this return type
                        delegate = new DefaultModelElement(node);
                    }
                }
                return Proxy.newProxyInstance(returnClass.getClassLoader(), new Class[] {returnClass}, new SimpleProxy(delegate));
            }
        }

        protected Class<?> toClass(Type type)
        {
            if (type instanceof Class<?>)
            {
                return (Class<?>) type;
            }
            else if (type instanceof ParameterizedType)
            {
                return toClass(((ParameterizedType) type).getRawType());
            }
            else if (type instanceof WildcardType)
            {
                return toClass(((WildcardType) type).getUpperBounds()[0]);
            }
            else
            {
                return Object.class;
            }
        }

        private boolean isObject(Class<?> type)
        {
            return Object.class.equals(type);
        }

        @Nullable
        private Method findMatchingMethod(Method method)
        {
            try
            {
                return delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
            }
            catch (NoSuchMethodException e)
            {
                return null;
            }
        }

    }

}
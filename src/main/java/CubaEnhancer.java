/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

/**
 * Enhances entity classes.
 */
public class CubaEnhancer {

    private static final String ENHANCED_TYPE = "com.haulmont.cuba.core.sys.CubaEnhanced";
    private static final String ENHANCED_DISABLED_TYPE = "com.haulmont.cuba.core.sys.CubaEnhancingDisabled";

    private static final String METAPROPERTY_ANNOTATION = "com.haulmont.chile.core.annotations.MetaProperty";

    private Log log = LogFactory.getLog(CubaEnhancer.class);

    private ClassPool pool;
    private String outputDir;

    public CubaEnhancer(ClassPool pool, String outputDir) throws NotFoundException, CannotCompileException {
        this.pool = pool;
        this.outputDir = outputDir;
    }

    public void run(String className) {
        try {
            CtClass cc = pool.get(className);

            CtClass superclass = cc.getSuperclass();
            while (superclass != null && !superclass.getName().equals("com.haulmont.chile.core.model.impl.AbstractInstance")) {
                superclass = superclass.getSuperclass();
            }
            if (superclass == null) {
                log.info("[CubaEnhancer] " + className + " is not an AbstractInstance and should not be enhanced");
                return;
            }

            for (CtClass intf : cc.getInterfaces()) {
                if (intf.getName().equals(ENHANCED_TYPE) || intf.getName().equals(CubaEnhancer.ENHANCED_DISABLED_TYPE)) {
                    log.info("[CubaEnhancer] " + className + " has already been enhanced or should not be enhanced at all");
                    return;
                }
            }

            log.info("[CubaEnhancer] enhancing " + className);
            enhanceSetters(cc);

            makeAutogeneratedAccessorsProtected(cc);

            cc.addInterface(pool.get("com.haulmont.cuba.core.sys.CubaEnhanced"));
            cc.writeFile(outputDir);
        } catch (NotFoundException | IOException | CannotCompileException | ClassNotFoundException e) {
            throw new RuntimeException("Error enhancing class " + className + ": " + e, e);
        }
    }

    private void enhanceSetters(CtClass ctClass) throws NotFoundException, CannotCompileException, ClassNotFoundException {
        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            final String name = ctMethod.getName();
            if (Modifier.isAbstract(ctMethod.getModifiers())
                    || !name.startsWith("set")
                    || ctMethod.getReturnType() != CtClass.voidType
                    || ctMethod.getParameterTypes().length != 1)
                continue;

            String fieldName = StringUtils.uncapitalize(name.substring(3));

            // check if the setter is for a persistent or transient property
            CtMethod persistenceMethod = null;
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                if (method.getName().equals("_persistence_set_" + fieldName)) {
                    persistenceMethod = method;
                    break;
                }
            }
            if (persistenceMethod == null) {
                // can be a transient property
                CtField ctField = null;
                CtField[] declaredFields = ctClass.getDeclaredFields();
                for (CtField field : declaredFields) {
                    if (field.getName().equals(fieldName)) {
                        ctField = field;
                        break;
                    }
                }
                if (ctField == null)
                    continue; // no field
                // check if the field is annotated with @MetaProperty
                // cannot use ctField.getAnnotation() because of problem with classpath in child projects
                AnnotationsAttribute annotationsAttribute =
                        (AnnotationsAttribute) ctField.getFieldInfo().getAttribute(AnnotationsAttribute.visibleTag);
                if (annotationsAttribute == null || annotationsAttribute.getAnnotation(METAPROPERTY_ANNOTATION) == null)
                    continue;
            }

            CtClass setterParamType = ctMethod.getParameterTypes()[0];

            if (setterParamType.isPrimitive()) {
                throw new IllegalStateException(
                        String.format("Unable to enhance field %s.%s with primitive type %s. Use type %s.",
                                ctClass.getName(), fieldName,
                                setterParamType.getSimpleName(), StringUtils.capitalize(setterParamType.getSimpleName())));
            }

            ctMethod.addLocalVariable("__prev", setterParamType);
            ctMethod.addLocalVariable("__new", setterParamType);

            ctMethod.insertBefore(
                    "__prev = this.get" + StringUtils.capitalize(fieldName) + "();"
            );

            ctMethod.insertAfter(
                    "__new = this.get" + StringUtils.capitalize(fieldName) + "();" +
                    "if (!com.haulmont.chile.core.model.utils.InstanceUtils.propertyValueEquals(__prev, __new)) {" +
                    "  this.propertyChanged(\"" + fieldName + "\", __prev, __new);" +
                    "}"
            );
        }
    }

    private void makeAutogeneratedAccessorsProtected(CtClass ctClass)
            throws NotFoundException, CannotCompileException, ClassNotFoundException {

        for (CtMethod method : ctClass.getDeclaredMethods()) {
            if (method.getName().startsWith("_persistence_get_")
                    || method.getName().startsWith("_persistence_set_")) {
                method.setModifiers(Modifier.setProtected(method.getModifiers()));

                log.debug("Set protected modifier for " + method.getLongName());
            }
        }
    }
}
/*******************************************************************************
 * Copyright (c) 1998, 2016 Oracle and/or its affiliates. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Oracle - initial API and implementation from Oracle TopLink
 *     19/04/2014-2.6 Lukas Jungmann
 *       - 429992: JavaSE 8/ASM 5.0.1 support (EclipseLink silently ignores Entity classes with lambda expressions)
 ******************************************************************************/
package org.eclipse.persistence.internal.jpa.weaving;

// J2SE imports
import java.lang.instrument.IllegalClassFormatException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.ProtectionDomain;
import java.util.Map;

import javax.persistence.spi.ClassTransformer;

import org.eclipse.persistence.config.SystemProperties;
import org.eclipse.persistence.internal.helper.Helper;
import org.eclipse.persistence.internal.libraries.asm.ClassReader;
import org.eclipse.persistence.internal.libraries.asm.ClassVisitor;
import org.eclipse.persistence.internal.libraries.asm.ClassWriter;
import org.eclipse.persistence.internal.libraries.asm.commons.SerialVersionUIDAdder;
import org.eclipse.persistence.internal.logging.StdErrLogger;
import org.eclipse.persistence.internal.security.PrivilegedAccessHelper;
import org.eclipse.persistence.internal.security.PrivilegedGetClassLoaderFromCurrentThread;
import org.eclipse.persistence.logging.SessionLog;
import org.eclipse.persistence.sessions.Session;

/**
 * INTERNAL:
 * This class performs dynamic byte code weaving: for each attribute
 * mapped with One To One mapping with Basic Indirection it substitutes the
 * original attribute's type for ValueHolderInterface.
 */
public class PersistenceWeaver implements ClassTransformer {

    /** Class name in JVM '/' format to {@link ClassDetails} map. */
    protected Map<String, ClassDetails> classDetailsMap;

    /**
     * INTERNAL:
     * Creates an instance of dynamic byte code weaver.
     * @param session EclipseLink session (not used so {@code null} value is OK).
     * @param classDetailsMap Class name to {@link ClassDetails} map.
     * @deprecated Session instance is no longer needed for logging. Will be removed in 2.8.
     */
    public PersistenceWeaver(final Session session, final Map<String, ClassDetails> classDetailsMap) {
        this.classDetailsMap = classDetailsMap;
    }

    /**
     * INTERNAL:
     * Creates an instance of dynamic byte code weaver.
     * @param classDetailsMap Class name to {@link ClassDetails} map.
     * @since 2.7
     */
    public PersistenceWeaver(final Map<String, ClassDetails> classDetailsMap) {
        this.classDetailsMap = classDetailsMap;
    }

    /**
     * INTERNAL:
     * Allow the weaver to be clear to release its referenced memory.
     * This is required because the class loader reference to the transformer will never gc.
     */
    public void clear() {
        this.classDetailsMap = null;
    }

    /**
     * INTERNAL:
     * Get Class name in JVM '/' format to {@link ClassDetails} map.
     * @return Class name in JVM '/' format to {@link ClassDetails} map.
     */
    public Map<String, ClassDetails> getClassDetailsMap() {
        return classDetailsMap;
    }

    /**
     * INTERNAL:
     * Perform dynamic byte code weaving of class.
     * @param loader              The defining loader of the class to be transformed, may be {@code null}
     *                            if the bootstrap loader.
     * @param className           The name of the class in the internal form of fully qualified class and interface
     *                            names.
     * @param classBeingRedefined If this is a redefine, the class being redefined, otherwise {@code null}.
     * @param protectionDomain    The protection domain of the class being defined or redefined.
     * @param classfileBuffer     The input byte buffer in class file format (must not be modified).
     * @return  A well-formed class file buffer (the result of the transform), or {@code null} if no transform
     *          is performed.
     */
    @Override
    public byte[] transform(final ClassLoader loader, final String className,
            final Class classBeingRedefined, final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) throws IllegalClassFormatException {
        // PERF: Is finest logging turned on?
        final boolean shouldLogFinest = StdErrLogger.shouldLog(SessionLog.FINEST, SessionLog.WEAVER);
        final Map classDetailsMap = this.classDetailsMap;
        // Check if cleared already.
        if (classDetailsMap == null) {
            return null;
        }
        try {
            /*
             * The ClassFileTransformer callback - when called by the JVM's
             * Instrumentation implementation - is invoked for every class loaded.
             * Thus, we must check the classDetailsMap to see if we are 'interested'
             * in the class.
             */
            final ClassDetails classDetails = (ClassDetails)classDetailsMap.get(Helper.toSlashedClassName(className));

            if (classDetails != null) {
                if (shouldLogFinest) {
                    StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "begin_weaving_class", className);
                }
                final ClassReader classReader = new ClassReader(classfileBuffer);
                final String reflectiveIntrospectionProperty =
                        PrivilegedAccessHelper.getSystemProperty(SystemProperties.WEAVING_REFLECTIVE_INTROSPECTION);
                final ClassWriter classWriter = reflectiveIntrospectionProperty != null
                        ? new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                        : new ComputeClassWriter(loader, ClassWriter.COMPUTE_FRAMES);
                final ClassWeaver classWeaver = new ClassWeaver(classWriter, classDetails);
                final ClassVisitor sv = new SerialVersionUIDAdder(classWeaver);
                if (shouldLogFinest) {
                    ClassLoader contextClassLoader;
                    if (PrivilegedAccessHelper.shouldUsePrivilegedAccess()) {
                        try {
                            contextClassLoader = AccessController.doPrivileged(
                                    new PrivilegedGetClassLoaderFromCurrentThread());
                        } catch (PrivilegedActionException ex) {
                            throw (RuntimeException) ex.getCause();
                        }
                    } else {
                        contextClassLoader = Thread.currentThread().getContextClassLoader();
                    }
                    if (reflectiveIntrospectionProperty != null) {
                        StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaving_init_class_writer",
                                className, Integer.toHexString(System.identityHashCode(contextClassLoader)));
                    } else {
                        StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaving_init_compute_class_writer",
                                className, Integer.toHexString(System.identityHashCode(contextClassLoader)),
                                loader != null ? Integer.toHexString(System.identityHashCode(loader)) : "null");
                    }
                }
                classReader.accept(sv, 0);
                if (classWeaver.alreadyWeaved) {
                    if (shouldLogFinest) {
                        StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "end_weaving_class", className);
                    }
                    return null;
                }
                if (classWeaver.weaved) {
                    final byte[] bytes = classWriter.toByteArray();
                    final String outputPath =
                            PrivilegedAccessHelper.getSystemProperty(SystemProperties.WEAVING_OUTPUT_PATH, "");

                    if (!outputPath.equals("")) {
                        Helper.outputClassFile(className, bytes, outputPath);
                    }
                    // PERF: Don't execute this set of if statements with logging turned off.
                    if (shouldLogFinest) {
                        if (classWeaver.weavedPersistenceEntity) {
                            StdErrLogger.log(
                                    SessionLog.FINEST, SessionLog.WEAVER, "weaved_persistenceentity", className);
                        }
                        if (classWeaver.weavedChangeTracker) {
                            StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaved_changetracker", className);
                        }
                        if (classWeaver.weavedLazy) {
                            StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaved_lazy", className);
                        }
                        if (classWeaver.weavedFetchGroups) {
                            StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaved_fetchgroups", className);
                        }
                        if (classWeaver.weavedRest) {
                            StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "weaved_rest", className);
                        }
                        StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "end_weaving_class", className);
                    }
                    return bytes;
                }
                if (shouldLogFinest) {
                    StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "end_weaving_class", className);
                }
            } else {
                if (shouldLogFinest) {
                    StdErrLogger.log(
                            SessionLog.FINEST, SessionLog.WEAVER, "transform_missing_class_details", className);
                }
            }
        } catch (Throwable exception) {
            if (StdErrLogger.shouldLog(SessionLog.WARNING, SessionLog.WEAVER)) {
                StdErrLogger.log(SessionLog.WARNING, SessionLog.WEAVER,
                        "exception_while_weaving", new Object[] {className, exception.getLocalizedMessage()});
                if (shouldLogFinest) {
                    StdErrLogger.logThrowable(SessionLog.FINEST, SessionLog.WEAVER, exception);
                }
            }
        }
        if (shouldLogFinest) {
            StdErrLogger.log(SessionLog.FINEST, SessionLog.WEAVER, "transform_existing_class_bytes", className);
        }
        // Returning null means 'use existing class bytes'.
        return null;
    }

    /**
     * INTERNAL:
     * Returns an unqualified class name from the specified class name.
     * @param name Class name with {@code '/'} as delimiter.
     * @return Unqualified class name.
     */
    protected static String getShortName(String name) {
        int pos  = name.lastIndexOf('/');
        if (pos >= 0) {
            name = name.substring(pos+1);
            if (name.endsWith(";")) {
                name = name.substring(0, name.length()-1);
            }
            return name;
        }
        return "";
    }

}

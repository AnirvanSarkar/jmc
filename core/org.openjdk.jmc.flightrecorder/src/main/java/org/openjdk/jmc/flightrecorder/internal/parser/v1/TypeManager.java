/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The contents of this file are subject to the terms of either the Universal Permissive License
 * v 1.0 as shown at https://oss.oracle.com/licenses/upl
 *
 * or the following license:
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openjdk.jmc.flightrecorder.internal.parser.v1;

import java.io.IOException;
import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.collection.FastAccessNumberMap;
import org.openjdk.jmc.common.unit.ContentType;
import org.openjdk.jmc.common.unit.IUnit;
import org.openjdk.jmc.common.unit.StructContentType;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.common.util.LabeledIdentifier;
import org.openjdk.jmc.flightrecorder.internal.InvalidJfrFileException;
import org.openjdk.jmc.flightrecorder.internal.parser.LoaderContext;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameFilter;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ChunkMetadata.AnnotatedElement;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ChunkMetadata.AnnotationElement;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ChunkMetadata.ClassElement;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ChunkMetadata.FieldElement;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrFrame;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrJavaClass;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrJavaClassLoader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrJavaModule;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrJavaPackage;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrMethod;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrOldObject;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrOldObjectArray;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrOldObjectField;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrOldObjectGcRoot;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrStackTrace;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrThread;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes.JfrThreadGroup;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.AbstractStructReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.ArrayReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.IValueReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.PoolReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.PrimitiveReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.QuantityReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.ReflectiveReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.StringReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.StructReader;
import org.openjdk.jmc.flightrecorder.internal.parser.v1.ValueReaders.TicksTimestampReader;
import org.openjdk.jmc.flightrecorder.internal.util.JfrInternalConstants;
import org.openjdk.jmc.flightrecorder.messages.internal.Messages;
import org.openjdk.jmc.flightrecorder.parser.IEventSink;
import org.openjdk.jmc.flightrecorder.parser.ValueField;

class TypeManager {

	private static class NopEventSink implements IEventSink {
		@Override
		public void addEvent(Object[] values) {
		}
	}

	private static class SkipFieldsEventSink implements IEventSink {
		private final IEventSink subSink;
		private final List<Integer> skipFields;
		private final Object[] reusableStruct;

		SkipFieldsEventSink(IEventSink subSink, List<Integer> skipFields, int fieldCount) {
			this.subSink = subSink;
			this.skipFields = skipFields;
			reusableStruct = new Object[fieldCount - skipFields.size()];
		}

		@Override
		public void addEvent(Object[] fieldValues) {
			Iterator<Integer> skipIter = skipFields.iterator();
			int skipNext = skipIter.next();
			int j = 0;
			for (int i = 0; i < fieldValues.length; i++) {
				if (i != skipNext) {
					reusableStruct[j++] = fieldValues[i];
				} else if (skipIter.hasNext()) {
					skipNext = skipIter.next();
				}
			}
			subSink.addEvent(reusableStruct);
		}
	}

	private class TypeEntry {
		private static final String STRUCT_TYPE_CLASS = "java.lang.Class"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_THREAD = "java.lang.Thread"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_STACK_TRACE = "com.oracle.jfr.types.StackTrace"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_STACK_TRACE_2 = "jdk.types.StackTrace"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_STACK_FRAME = "com.oracle.jfr.types.StackFrame"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_STACK_FRAME_2 = "jdk.types.StackFrame"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_METHOD = "com.oracle.jfr.types.Method"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_METHOD_2 = "jdk.types.Method"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_CLASS_LOADER = "com.oracle.jfr.types.ClassLoader"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_CLASS_LOADER_2 = "jdk.types.ClassLoader"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_MODULE = "com.oracle.jfr.types.Module"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_MODULE_2 = "jdk.types.Module"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_PACKAGE = "com.oracle.jfr.types.Package"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_PACKAGE_2 = "jdk.types.Package"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT = "com.oracle.jfr.types.OldObject"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_2 = "jdk.types.OldObject"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_ARRAY = "com.oracle.jfr.types.OldObjectArray"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_ARRAY_2 = "jdk.types.OldObjectArray"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_FIELD = "com.oracle.jfr.types.OldObjectField"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_FIELD_2 = "jdk.types.OldObjectField"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_GC_ROOT = "com.oracle.jfr.types.OldObjectGcRoot"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_OLD_OBJECT_GC_ROOT_2 = "jdk.types.OldObjectGcRoot"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_THREAD_GROUP = "com.oracle.jfr.types.ThreadGroup"; //$NON-NLS-1$
		private static final String STRUCT_TYPE_THREAD_GROUP_2 = "jdk.types.ThreadGroup"; //$NON-NLS-1$

		final ClassElement element;
		final LoaderContext context;
		final FastAccessNumberMap<Object> constants;
		private IValueReader reader;

		TypeEntry(ClassElement element, LoaderContext context) {
			this(element, context, new FastAccessNumberMap<>());
		}

		/**
		 * Temporary constructor for sharing constants. Only used for Strings.
		 */
		TypeEntry(ClassElement element, LoaderContext context, FastAccessNumberMap<Object> constants) {
			this.element = element;
			this.context = context;
			this.constants = constants;
		}

		public IValueReader getReader() throws InvalidJfrFileException {
			if (reader == null) {
				int fieldCount = element.getFieldCount();
				if (element.isSimpleType() && fieldCount == 1) {
					FieldElement singleField = element.fields.get(0);
					if (singleField.classId == element.classId) {
						throw new InvalidJfrFileException(
								element.typeIdentifier + " is a simple type referring to itself"); //$NON-NLS-1$
					} else {
						reader = createFieldReader(element.fields.get(0), null, context, element.typeIdentifier);
					}
				} else if (fieldCount == 0 && element.superType == null) {
					if (StringReader.STRING.equals(element.typeIdentifier)) {
						reader = new StringReader(constants);
					} else {
						reader = new PrimitiveReader(element.typeIdentifier);
					}
				} else {
					AbstractStructReader typeReader = element.typeIdentifier.startsWith("jdk.") //$NON-NLS-1$
							? createStructReaderV2(element.typeIdentifier, element.label, element.description,
									fieldCount)
							: createStructReaderV1(element.typeIdentifier, element.label, element.description,
									fieldCount);
					// assign before resolving field since it may be recursive
					reader = typeReader;
					for (int i = 0; i < fieldCount; i++) {
						FieldElement fe = element.fields.get(i);
						IValueReader reader = createFieldReader(fe, null, context, element.typeIdentifier);
						String labelOrId = (fe.label == null) ? fe.fieldIdentifier : fe.label;
						typeReader.addField(fe.fieldIdentifier, labelOrId, fe.description, reader);
					}
				}
			}
			return reader;
		}

		private AbstractStructReader createStructReaderV2(
			String identifier, String name, String description, int fieldCount) {
			switch (identifier) {
			case STRUCT_TYPE_THREAD_GROUP_2:
				return new ReflectiveReader(JfrThreadGroup.class, fieldCount, UnitLookup.THREAD_GROUP);
			case STRUCT_TYPE_CLASS_LOADER_2:
				return new ReflectiveReader(JfrJavaClassLoader.class, fieldCount, UnitLookup.CLASS_LOADER);
			case STRUCT_TYPE_OLD_OBJECT_GC_ROOT_2:
				return new ReflectiveReader(JfrOldObjectGcRoot.class, fieldCount, UnitLookup.OLD_OBJECT_GC_ROOT);
			case STRUCT_TYPE_OLD_OBJECT_2:
				return new ReflectiveReader(JfrOldObject.class, fieldCount, UnitLookup.OLD_OBJECT);
			case STRUCT_TYPE_OLD_OBJECT_ARRAY_2:
				return new ReflectiveReader(JfrOldObjectArray.class, fieldCount, UnitLookup.OLD_OBJECT_ARRAY);
			case STRUCT_TYPE_OLD_OBJECT_FIELD_2:
				return new ReflectiveReader(JfrOldObjectField.class, fieldCount, UnitLookup.OLD_OBJECT_FIELD);
			case STRUCT_TYPE_METHOD_2:
				return new ReflectiveReader(JfrMethod.class, fieldCount, UnitLookup.METHOD);
			case STRUCT_TYPE_STACK_FRAME_2:
				return new SpecificReaders.StackFrame2Reader(JfrFrame.class, fieldCount, UnitLookup.STACKTRACE_FRAME);
			case STRUCT_TYPE_STACK_TRACE_2:
				return createFilteringStackTraceReader(fieldCount);
			case STRUCT_TYPE_MODULE_2:
				return new ReflectiveReader(JfrJavaModule.class, fieldCount, UnitLookup.MODULE);
			case STRUCT_TYPE_PACKAGE_2:
				return new ReflectiveReader(JfrJavaPackage.class, fieldCount, UnitLookup.PACKAGE);
			default:
				return createDefaultStructReader(fieldCount);
			}
		}

		private AbstractStructReader createDefaultStructReader(int fieldCount) {
			synchronized (structTypes) {
				StructContentType<Object[]> structType = structTypes.get(element.classId);
				if (structType == null) {
					// Note that these struct types won't have localized names - so unless there really is a label
					// set, we don't really care and set label to identifier.
					structType = new StructContentType<>(element.typeIdentifier,
							element.label != null ? element.label : element.typeIdentifier, element.description);
					structTypes.put(element.classId, structType);
				}
				return new StructReader(structType, fieldCount);
			}
		}

		private AbstractStructReader createStructReaderV1(
			String identifier, String name, String description, int fieldCount) {
			switch (identifier) {
			case STRUCT_TYPE_THREAD:
				return new ReflectiveReader(JfrThread.class, fieldCount, UnitLookup.THREAD);
			case STRUCT_TYPE_THREAD_GROUP:
				return new ReflectiveReader(JfrThreadGroup.class, fieldCount, UnitLookup.THREAD_GROUP);
			case STRUCT_TYPE_CLASS:
				return new ReflectiveReader(JfrJavaClass.class, fieldCount, UnitLookup.CLASS);
			case STRUCT_TYPE_CLASS_LOADER:
				return new ReflectiveReader(JfrJavaClassLoader.class, fieldCount, UnitLookup.CLASS_LOADER);
			case STRUCT_TYPE_OLD_OBJECT_GC_ROOT:
				return new ReflectiveReader(JfrOldObjectGcRoot.class, fieldCount, UnitLookup.OLD_OBJECT_GC_ROOT);
			case STRUCT_TYPE_OLD_OBJECT:
				return new ReflectiveReader(JfrOldObject.class, fieldCount, UnitLookup.OLD_OBJECT);
			case STRUCT_TYPE_OLD_OBJECT_ARRAY:
				return new ReflectiveReader(JfrOldObjectArray.class, fieldCount, UnitLookup.OLD_OBJECT_ARRAY);
			case STRUCT_TYPE_OLD_OBJECT_FIELD:
				return new ReflectiveReader(JfrOldObjectField.class, fieldCount, UnitLookup.OLD_OBJECT_FIELD);
			case STRUCT_TYPE_METHOD:
				return new ReflectiveReader(JfrMethod.class, fieldCount, UnitLookup.METHOD);
			case STRUCT_TYPE_STACK_FRAME:
				return new ReflectiveReader(JfrFrame.class, fieldCount, UnitLookup.STACKTRACE_FRAME);
			case STRUCT_TYPE_STACK_TRACE:
				return createFilteringStackTraceReader(fieldCount);
			case STRUCT_TYPE_MODULE:
				return new ReflectiveReader(JfrJavaModule.class, fieldCount, UnitLookup.MODULE);
			case STRUCT_TYPE_PACKAGE:
				return new ReflectiveReader(JfrJavaPackage.class, fieldCount, UnitLookup.PACKAGE);
			default:
				return createDefaultStructReader(fieldCount);
			}
		}

		private AbstractStructReader createFilteringStackTraceReader(int fieldCount) {
			FrameFilter frameFilter = context.getFrameFilter();
			if (frameFilter == null || frameFilter == FrameFilter.INCLUDE_ALL) {
				return new ReflectiveReader(JfrStackTrace.class, fieldCount, UnitLookup.STACKTRACE);
			} else {
				return new FilteringStackTraceReader(fieldCount, frameFilter);
			}
		}

		void resolveConstants() throws InvalidJfrFileException {
			IValueReader r = reader;
			if (r != null) {
				for (Object c : constants) {
					r.resolve(c);
					// FIXME: During resolve, some constants may become equal. Should we ensure canonical constants?
				}
			}
			context.addTypeConstantPool(element.classId, element.typeIdentifier, constants);
		}

		void readConstant(IDataInput input) throws InvalidJfrFileException, IOException {
			// FIXME: Constant lookup can perhaps be optimized (across chunks)
			long start = input.getPosition();
			long constantIndex = input.readLong();
			Object value = constants.get(constantIndex);
			if (value == null) {
				value = getReader().read(input, true);
				value = context.constantRead(constantIndex, value, element.typeIdentifier);
				constants.put(constantIndex, value);
			} else {
				getReader().skip(input);
			}
			long end = input.getPosition();
			context.addEntryPoolSize(element.typeIdentifier, end - start);
		}
	}

	private class EventTypeEntry {
		private final ClassElement element;
		private final List<IValueReader> valueReaders;
		private Object[] reusableStruct;
		private IEventSink eventSink;
		private LabeledIdentifier eventType;
		private LoaderContext context;

		EventTypeEntry(ClassElement element) {
			this.element = element;
			valueReaders = new ArrayList<>(element.getFieldCount());
		}

		void readEvent(IDataInput input) throws InvalidJfrFileException, IOException {
			for (int i = 0; i < valueReaders.size(); i++) {
				reusableStruct[i] = valueReaders.get(i).read(input, false);
			}
			eventSink.addEvent(reusableStruct);
		}

		LabeledIdentifier getValueType() {
			if (eventType == null) {
				eventType = new LabeledIdentifier(element.typeIdentifier, element.classId, element.label,
						element.description);
			}
			return eventType;
		}

		void init(LoaderContext context) throws InvalidJfrFileException, IOException {
			this.context = context;
			if (context.hideExperimentals() && element.experimental) {
				eventSink = new NopEventSink();
			} else {
				List<ValueField> fieldsList = new ArrayList<>();
				List<Integer> skipFields = new ArrayList<>();
				for (int i = 0; i < element.getFieldCount(); i++) {
					FieldElement fe = element.fields.get(i);
					String valueType = context.getValueInterpretation(element.typeIdentifier, fe.fieldIdentifier);
					IValueReader reader = createFieldReader(fe, valueType, context, element.typeIdentifier);
					String fieldLabel = buildLabel(fe.fieldIdentifier, fe);
					if (context.hideExperimentals() && fe.experimental) {
						valueReaders.add(reader);
						skipFields.add(i);
					} else if (reader instanceof StructReader) {
						// Flattening of nested structs
						ClassElement fieldType = getTypeEntry(fe.classId).element;
						for (int j = 0; j < fieldType.getFieldCount(); j++) {
							FieldElement nestedField = fieldType.fields.get(j);
							String nestedId = fe.fieldIdentifier + ":" + nestedField.fieldIdentifier; //$NON-NLS-1$
							String nestedValueType = context.getValueInterpretation(element.typeIdentifier, nestedId);
							IValueReader nestedReader = createFieldReader(nestedField, nestedValueType, context,
									element.typeIdentifier);
							valueReaders.add(nestedReader);
							String nestedLabel = fieldLabel + " : " //$NON-NLS-1$
									+ (nestedField.label == null ? nestedField.fieldIdentifier : nestedField.label);
							fieldsList.add(new ValueField(nestedId, nestedLabel, nestedField.description,
									nestedReader.getContentType()));
						}
					} else {
						valueReaders.add(reader);
						fieldsList.add(new ValueField(fe.fieldIdentifier, fieldLabel, fe.description,
								reader.getContentType()));
					}
				}
				String typeLabel = buildLabel(element.typeIdentifier, element);
				// FIXME: Consider making the category array into something else, like an event type metadata array?
				eventSink = context.getSinkFactory().create(element.typeIdentifier, typeLabel, element.category,
						element.description, fieldsList);
				reusableStruct = new Object[valueReaders.size()];
				if (skipFields.size() > 0) {
					eventSink = new SkipFieldsEventSink(eventSink, skipFields, reusableStruct.length);
				}
			}
		}

		void updateEventStats(long size) {
			context.updateEventStats(element.typeIdentifier, size);
		}
	}

	// NOTE: Using constant pool id as identifier.
	private final Map<Long, StructContentType<Object[]>> structTypes = new HashMap<>();
	private final FastAccessNumberMap<TypeEntry> otherTypes = new FastAccessNumberMap<>();
	private final FastAccessNumberMap<EventTypeEntry> eventTypes = new FastAccessNumberMap<>();
	private final LoaderContext context;
	private final ChunkStructure header;
	private long skippedEventCount;

	TypeManager(List<ClassElement> classList, LoaderContext context, ChunkStructure header)
			throws InvalidJfrFileException, IOException {
		this.context = context;
		this.header = header;
		for (ClassElement ce : classList) {
			if (ce.isEventType()) {
				eventTypes.put(ce.classId, new EventTypeEntry(ce));
			} else {
				otherTypes.put(ce.classId, new TypeEntry(ce, context));
			}
		}
		for (ClassElement ce : classList) {
			resolveAnnotations(ce);
			for (int i = 0; i < ce.getFieldCount(); i++) {
				resolveAnnotations(ce.fields.get(i));
			}
		}

		for (EventTypeEntry ce : eventTypes) {
			ce.init(context);
		}
	}

	void readEvent(long typeId, IDataInput input, long size) throws InvalidJfrFileException, IOException {
		EventTypeEntry entry = eventTypes.get(typeId);
		if (entry == null) {
			// We don't need to do anything here, as the chunk loader will skip to the next event for us.
			Logger.getLogger(getClass().getName()).log(Level.WARNING,
					"Event type with id " + typeId + " was not declared"); //$NON-NLS-1$ //$NON-NLS-2$
			skippedEventCount++;
		} else {
			entry.readEvent(input);
			entry.updateEventStats(size);
		}
	}

	void readConstants(long typeId, IDataInput input, int constantCount) throws InvalidJfrFileException, IOException {
		TypeEntry entry = getTypeEntry(typeId);
		for (int j = 0; j < constantCount; j++) {
			entry.readConstant(input);
		}
	}

	void resolveConstants() throws InvalidJfrFileException {
		Map<String, FastAccessNumberMap<Object>> pools = new HashMap<>();
		for (TypeEntry classEntry : otherTypes) {
			classEntry.resolveConstants();
			pools.put(classEntry.element.typeIdentifier, classEntry.constants);
		}
		context.allConstantPoolsResolved(pools);
	}

	long getSkippedEventCount() {
		return skippedEventCount;
	}

	private TypeEntry getTypeEntry(long typeId) throws InvalidJfrFileException {
		TypeEntry entry = otherTypes.get(typeId);
		if (entry == null) {
			throw new InvalidJfrFileException("Class with id " + typeId + " was not declared"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return entry;
	}

	private void resolveAnnotations(AnnotatedElement ae) throws InvalidJfrFileException {
		if (ae.annotations != null) {
			for (AnnotationElement a : ae.annotations) {
				ClassElement annotationType = getTypeEntry(a.classId).element;
				ae.resolveAnnotation(annotationType.typeIdentifier, a.values);
			}
		}
	}

	private IValueReader createFieldReader(FieldElement f, String valueType, LoaderContext context, String eventTypeId)
			throws InvalidJfrFileException {
		TypeEntry fieldType = getTypeEntry(f.classId);
		String typeIdentifier = fieldType.element.typeIdentifier;
		boolean isNumeric = PrimitiveReader.isNumeric(typeIdentifier);
		boolean isLong = PrimitiveReader.isLong(typeIdentifier);
		IValueReader reader = fieldType.getReader();
		if (f.ticksUnitKind == UnitLookup.TIMESPAN) {
			reader = new QuantityReader(typeIdentifier, header.getTicksTimespanUnit(), f.unsigned);
		} else if (f.ticksUnitKind == UnitLookup.TIMESTAMP) {
			reader = new TicksTimestampReader(typeIdentifier, header, f.unsigned);
		} else if (f.unit != null) {
			reader = new QuantityReader(typeIdentifier, f.unit, f.unsigned);
		} else if (isNumeric) {
			if (JfrInternalConstants.TYPE_IDENTIFIER_VALUE_INTERPRETATION.equals(valueType)) {
				reader = new TypeIdentifierReader(typeIdentifier, f.unsigned);
			} else {
				if (JfrInternalConstants.LINE_NUMBER_ID.equals(f.fieldIdentifier)
						|| JfrInternalConstants.BCI_ID.equals(f.fieldIdentifier)
						|| JfrInternalConstants.MODIFIERS_ID.equals(f.fieldIdentifier)
						|| JfrInternalConstants.JAVA_THREAD_ID_ID.equals(f.fieldIdentifier)
						|| JfrInternalConstants.CERTIFICATE_ID_ID.equals(f.fieldIdentifier) || isLong) {
					reader = new PrimitiveReader(typeIdentifier);
				} else {
					IUnit unit = UnitLookup.getUnitOrNull(valueType);
					reader = new QuantityReader(typeIdentifier, unit == null ? UnitLookup.NUMBER_UNITY : unit,
							f.unsigned);
				}
			}
		}
		if (f.isStoredInPool()) {
			if (isNumeric) {
				throw new InvalidJfrFileException("Numerics should not be put in constant pools"); //$NON-NLS-1$
			}
			reader = new PoolReader(fieldType.constants, reader.getContentType(), context, typeIdentifier, eventTypeId);
		}
		return f.isArray() ? new ArrayReader(reader, header) : reader;
	}

	private static String buildLabel(String id, AnnotatedElement element) {
		String labelOrId = element.label == null ? id : element.label;
		return element.experimental
				? MessageFormat.format(Messages.getString(Messages.TypeManager_EXPERIMENTAL_TYPE), labelOrId)
				: labelOrId;
	}

	private class TypeIdentifierReader implements IValueReader {
		private final String typeIdentifier;
		private final boolean unsigned;

		TypeIdentifierReader(String typeIdentifier, boolean unsigned) throws InvalidJfrFileException {
			this.typeIdentifier = typeIdentifier;
			this.unsigned = unsigned;
		}

		@Override
		public Object read(IDataInput in, boolean allowUnresolvedReference)
				throws IOException, InvalidJfrFileException {
			long typeId = PrimitiveReader.readLong(in, typeIdentifier, unsigned);
			return eventTypes.get(typeId).getValueType();
		}

		@Override
		public Object resolve(Object value) throws InvalidJfrFileException {
			return value;
		}

		@Override
		public void skip(IDataInput in) throws IOException, InvalidJfrFileException {
			PrimitiveReader.readLong(in, typeIdentifier, unsigned);
		}

		@Override
		public ContentType<?> getContentType() {
			return UnitLookup.LABELED_IDENTIFIER;
		}
	}

	/**
	 * Custom StackTrace reader that filters frames during parsing to avoid creating unnecessary
	 * frame objects.
	 */
	private static class FilteringStackTraceReader extends ReflectiveReader {
		private final FrameFilter frameFilter;
		private final List<Field> fields;
		private int framesFieldIndex = -1;

		public FilteringStackTraceReader(int fieldCount, FrameFilter frameFilter) {
			super(JfrStackTrace.class, fieldCount, UnitLookup.STACKTRACE);
			this.frameFilter = frameFilter;
			this.fields = new ArrayList<>(fieldCount);
		}

		@Override
		public Object read(IDataInput in, boolean allowUnresolvedReference)
				throws IOException, InvalidJfrFileException {
			try {
				Object instance = new JfrStackTrace();
				for (int i = 0; i < valueReaders.size(); i++) {
					Object val = valueReaders.get(i).read(in, allowUnresolvedReference);

					// Apply filtering to frames field only if references are resolved and we have actual frames
					if (i == framesFieldIndex && val instanceof Object[] && frameFilter != FrameFilter.INCLUDE_ALL
							&& !allowUnresolvedReference) {
						val = filterFrames((Object[]) val);
					}

					Field f = fields.get(i);
					if (f != null) {
						f.set(instance, val);
					}
				}
				return instance;
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Object resolve(Object value) throws InvalidJfrFileException {
			Object resolved = super.resolve(value);

			// Apply filtering after resolution if this is a JfrStackTrace
			if (resolved instanceof JfrStackTrace && frameFilter != FrameFilter.INCLUDE_ALL) {
				JfrStackTrace stackTrace = (JfrStackTrace) resolved;
				try {
					Field framesField = JfrStackTrace.class.getField("frames");
					Object frames = framesField.get(stackTrace);
					if (frames instanceof Object[]) {
						Object[] filteredFrames = filterFrames((Object[]) frames);
						framesField.set(stackTrace, filteredFrames);
					}
				} catch (NoSuchFieldException | IllegalAccessException e) {
					// If we can't access the frames field, just return the unfiltered stacktrace
				}
			}

			return resolved;
		}

		private Object[] filterFrames(Object[] frames) {
			List<Object> filteredFrames = new ArrayList<>(frames.length);
			for (Object frameObj : frames) {
				if (frameObj instanceof IMCFrame) {
					IMCFrame frame = (IMCFrame) frameObj;
					if (frameFilter.shouldInclude(frame)) {
						filteredFrames.add(frame);
					}
				} else {
					// If it's not an IMCFrame, include it anyway
					filteredFrames.add(frameObj);
				}
			}
			return filteredFrames.toArray(new Object[0]);
		}

		@Override
		void addField(String identifier, String name, String description, IValueReader reader)
				throws InvalidJfrFileException {
			super.addField(identifier, name, description, reader);

			// Track which field is the frames field
			if ("frames".equals(identifier)) {
				framesFieldIndex = fields.size();
			}

			// Get the field using reflection on JfrStackTrace
			Field field = null;
			try {
				field = JfrStackTrace.class.getField(identifier);
			} catch (NoSuchFieldException e) {
				try {
					field = JfrStackTrace.class.getField("_" + identifier);
				} catch (NoSuchFieldException e2) {
					// Field not found - this matches the parent's behavior
				}
			}
			fields.add(field);
		}
	}
}

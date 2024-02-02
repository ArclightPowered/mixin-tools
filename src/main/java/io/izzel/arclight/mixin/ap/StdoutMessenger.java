package io.izzel.arclight.mixin.ap;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

// From MixinExtras, MIT License
// https://github.com/LlamaLad7/MixinExtras/blob/3fa815df01e516f8cfc59053ab12ab227344f781/src/main/java/com/llamalad7/mixinextras/ap/StdoutMessager.java
// No changes
public class StdoutMessenger implements Messager {
    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        System.out.printf("[%s] %s%n", kind.name(), msg);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
        printMessage(kind, msg);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        printMessage(kind, msg);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
        printMessage(kind, msg);
    }
}
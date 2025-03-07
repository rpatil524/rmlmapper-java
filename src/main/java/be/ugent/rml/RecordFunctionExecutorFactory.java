package be.ugent.rml;

import be.ugent.rml.extractor.ConstantExtractor;
import be.ugent.rml.extractor.ReferenceExtractor;
import be.ugent.rml.functions.ConcatFunction;
import be.ugent.rml.functions.SingleRecordFunctionExecutor;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.term.NamedNode;
import be.ugent.rml.term.Term;

import java.util.List;

public class RecordFunctionExecutorFactory {

    public static SingleRecordFunctionExecutor generate(QuadStore store, Term termMap, boolean encodeURI, boolean ignoreDoubleQuotes, boolean strictReferenceResolution) {
        List<Term> references = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "reference"), null));
        List<Term> templates = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "template"), null));
        List<Term> constants = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "constant"), null));

        if (!references.isEmpty()) {
            return new ReferenceExtractor(references.get(0).getValue(), ignoreDoubleQuotes, strictReferenceResolution);
        } else if (!templates.isEmpty()) {
            return new ConcatFunction(Utils.parseTemplate(templates.get(0).getValue(), ignoreDoubleQuotes, strictReferenceResolution), encodeURI);
        } else if (!constants.isEmpty()) {
            return new ConstantExtractor(constants.get(0).getValue());
        } else {
            return null;
        }
    }
}

package be.ugent.rml.target;

import be.ugent.rml.store.Quad;

import java.io.IOException;
import java.util.List;
import java.util.Map;


public class SolidAclTarget extends SolidTarget {

    public SolidAclTarget(Map<String, Object> solidTargetInfo, String serializationFormat, List<Quad> metadata) throws IOException {
        super(solidTargetInfo, serializationFormat, metadata);
        solidHelperPath = "addAcl";
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof SolidAclTarget) {
            SolidAclTarget target  = (SolidAclTarget) o;
            return this.solidTargetInfo.get("resourceUrl").equals(target.getSolidTargetInfo().get("resourceUrl"));
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "acl for " + this.solidTargetInfo.get("resourceUrl");
    }
}

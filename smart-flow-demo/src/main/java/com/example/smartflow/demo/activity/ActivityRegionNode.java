package com.example.smartflow.demo.activity;

import com.example.smartflow.demo.MinshengNames;
import com.example.smartflow.flow.TaskFrame;
import com.example.smartflow.node.NodeSupport;
import com.example.smartflow.node.slot.SlotFillingNode;
import com.example.smartflow.node.slot.SlotSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/** 短信活动的发送区域。作为 {@link ActivityCreateNode} 的成员，和其他三项没有先后顺序。 */
@Component
public class ActivityRegionNode extends SlotFillingNode {
    public ActivityRegionNode(NodeSupport support) { super(support); }

    @Override public String id() { return MinshengNames.ACTIVITY_REGION; }
    @Override protected String stageGoal() { return "收集短信发送区域"; }
    /** 作为组成员时不会单独推进；万一被单独执行，收齐后回到活动创建组 */
    @Override protected String nextNode() { return MinshengNames.ACTIVITY_CREATE; }

    @Override protected List<SlotSpec> slots() {
        return List.of(SlotSpec.string("region", "发送区域").maxLength(100)
                .describe("保留用户原话，例如“杭州市西湖区蒋村街道”；不自动纠正街道名，不补造行政区划")
                .build());
    }

    @Override protected String askItem(String label, TaskFrame task) {
        return "发送区域（例如：杭州市西湖区蒋村街道）";
    }
}

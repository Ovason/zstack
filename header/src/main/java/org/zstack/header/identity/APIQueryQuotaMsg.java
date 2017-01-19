package org.zstack.header.identity;

import org.springframework.http.HttpMethod;
import org.zstack.header.query.APIQueryMessage;
import org.zstack.header.query.AutoQuery;
import org.zstack.header.query.QueryCondition;
import org.zstack.header.rest.RestRequest;

import static org.zstack.utils.CollectionDSL.list;

/**
 * Created by frank on 7/14/2015.
 */
@AutoQuery(replyClass = APIQueryQuotaReply.class, inventoryClass = QuotaInventory.class)
@Action(category = AccountConstant.ACTION_CATEGORY, names = {"read"})
@RestRequest(
        path = "/accounts/quotas",
        method = HttpMethod.GET,
        responseClass = APIQueryQuotaReply.class
)
public class APIQueryQuotaMsg extends APIQueryMessage {
 
    public static APIQueryQuotaMsg __example__() {
        APIQueryQuotaMsg msg = new APIQueryQuotaMsg();
        QueryCondition queryCondition = new QueryCondition();
        queryCondition.setName("name");
        queryCondition.setOp("=");
        queryCondition.setValue("test");

        msg.setConditions(list(queryCondition));

        return msg;
    }

}

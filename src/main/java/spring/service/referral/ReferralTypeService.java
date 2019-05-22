package spring.service.referral;

import spring.service.common.BaseObjectService;
import us.mn.state.health.lims.referral.valueholder.ReferralType;

public interface ReferralTypeService extends BaseObjectService<ReferralType> {

	ReferralType getReferralTypeByName(String name);
}
package nc.impl.wa.paydata.precacu;

/**
 * PCB Group ��˰
 */
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nc.bs.dao.BaseDAO;
import nc.bs.dao.DAOException;
import nc.bs.logging.Logger;
import nc.bs.mw.sqltrans.TempTable;
import nc.impl.wa.paydata.caculate.AbstractFormulaExecutor;
import nc.impl.wa.paydata.tax.IMalaysiaPCBTaxInfPreProcess;
import nc.jdbc.framework.ConnectionFactory;
import nc.jdbc.framework.SQLParameter;
import nc.jdbc.framework.crossdb.CrossDBConnection;
import nc.md.data.access.NCObject;
import nc.md.persist.framework.MDPersistenceService;
import nc.pub.templet.converter.util.helper.ExceptionUtils;
import nc.vo.am.common.util.UFDoubleUtils;
import nc.vo.bd.defdoc.DefdocVO;
import nc.vo.bd.defdoc.DefdoclistVO;
import nc.vo.pub.BusinessException;
import nc.vo.pub.lang.UFDate;
import nc.vo.pub.lang.UFDouble;
import nc.vo.pubapp.pattern.pub.SqlBuilder;
import nc.vo.wa.item.MalaysiaVO_PCB;
import nc.vo.wa.item.MalaysiaVO_PCB_Rate;
import nc.vo.wa.item.MalaysiaVO_PCB_para;
import nc.vo.wa.item.UFDoubleScaleUtils;
import nc.vo.wa.pub.WaLoginContext;

public class MY_NormalPCBTaxInfPreProcess extends AbstractFormulaExecutor implements
		IMalaysiaPCBTaxInfPreProcess {
	
	private BaseDAO dao = null;
	//��resident��Non-Resident (δ��182�죩��C: Returning Exper Programe(REP)��D: Knowledge worker(IRDA)
	//˰�ʱ�����category1��3��2.����
	private ConcurrentHashMap<String, String> my_pcbcategoryandgroup;
	
	public MY_NormalPCBTaxInfPreProcess() {
		if(dao == null) {
			dao = new BaseDAO();
			initData();
		}
	}
	
	public void initData() {
		//�Ȳ�bd_defdoclist�õ�pk_defdoclist
		my_pcbcategoryandgroup = new ConcurrentHashMap<String, String>();
		SqlBuilder sb = new SqlBuilder();
		sb.append("code", new String[] {"SEALOCAL003", "SEALOCAL006"});
		sb.append(" and dr=0");
		try{
			NCObject[] deflistvos = MDPersistenceService.lookupPersistenceQueryService().
					queryBillOfNCObjectByCond(DefdoclistVO.class, sb.toString(), false);
			List<String> deflistpks = new ArrayList<String>();
			if(deflistvos != null && deflistvos.length >0 
					&& deflistvos[0].getContainmentObject() instanceof DefdoclistVO) {
				for(NCObject deflist : deflistvos) {
					deflistpks.add(((DefdoclistVO)deflist.getContainmentObject()).getPk_defdoclist());
				}
			}
			sb = new SqlBuilder();
			sb.append("pk_defdoclist", deflistpks.toArray(new String[0]));
			sb.append(" and dr = 0");
			NCObject[] defvos = MDPersistenceService.lookupPersistenceQueryService().queryBillOfNCObjectByCond(DefdocVO.class, sb.toString(), false);
			if(defvos != null && defvos.length >0 
					&& defvos[0].getContainmentObject() instanceof DefdocVO) {
			for(NCObject defvo : defvos) {
				my_pcbcategoryandgroup.put(((DefdocVO)defvo.getContainmentObject()).getPk_defdoc(), ((DefdocVO)defvo.getContainmentObject()).getCode());
				}
			}
			} catch(Exception e) {
				ExceptionUtils.wrapException(e.getMessage(), e);
		}

	}
	@Override
	public void transferTaxCacuData(MalaysiaTaxFormulaVO malaysiaFormulaVO,
			WaLoginContext context) throws BusinessException {
		//1.��ѯ��֪��н����Ŀ
		List<MalaysiaVO_PCB> results = this.queryPCBKnownItems(malaysiaFormulaVO, context);
		
		//2.�߼�����δ��ѯ��н����Ŀ������P
		ConcurrentHashMap<String, UFDouble> cacuOtherPCBItem = this.cacuOtherPCBItem(results.toArray(new MalaysiaVO_PCB[0]), context);
		
		//3.����wa_cacu_data, cacu_calue�����Ǿ���
		this.updateCacudata(cacuOtherPCBItem);

	}
	
	/**������Ҫ������ʱ��,��������
	 * ���� wa_cacu_data
	 * @param cacuOtherPCBItem
	 * @throws BusinessException 
	 */
	private void updateCacudata(ConcurrentHashMap<String, UFDouble> cacuOtherPCBItem) throws BusinessException {
		//������ʱ��
		this.creatTempTable(cacuOtherPCBItem);
		
		 String sql = " update wa_cacu_data w"
			+ " set (w.cacu_value) ="
			+ " (select p.pcb from tmp_wa_pcb p where w.pk_cacu_data = p.pk_cacu_data)"
			+ " where exists (select 1 from tmp_wa_pcb)";
		 super.executeSQLs(sql);
	}

	private void creatTempTable(ConcurrentHashMap<String, UFDouble> cacuOtherPCBItem) {
		Connection con = null;
		String tempTable = null;
		// ������ʱ��
		try {
			con = ConnectionFactory.getConnection();
			String tableName = "tmp_wa_pcb";
			String columns = "pk_cacu_data varchar2(20), pcb number(28,8)";
			tempTable = new TempTable().createTempTable(con, tableName, columns, "pk_cacu_data");
//			((CrossDBConnection) con).setAddTimeStamp(false);
			prepareTempTable(tempTable, cacuOtherPCBItem);
		} catch (SQLException e) {
			ExceptionUtils.wrapException(e.getMessage(), e);
		}
	}

	private void prepareTempTable(String tempTable,
			ConcurrentHashMap<String, UFDouble> cacuOtherPCBItem) {
		String sql = "insert into " + tempTable + " (pk_cacu_data,pcb) values (?,?)";
		PreparedStatement stm = null;
		Connection conn = null;
		try {
			conn = ConnectionFactory.getConnection();
			((CrossDBConnection) conn).setAddTimeStamp(false);
			stm = conn.prepareStatement(sql);
			for (Map.Entry<String, UFDouble> entry : cacuOtherPCBItem.entrySet()) {
				stm.setString(1, entry.getKey());
				stm.setDouble(2, entry.getValue().doubleValue());
				stm.addBatch();
			}
			stm.executeBatch();
		} catch (Exception e) {
			Logger.error(e.getMessage(), e);
		} finally {
			try {
				if (stm != null) {
					stm.close();
				}
			} catch (Exception e) {
			}
			try {
				if (conn != null) {
					conn.close();
				}
			} catch (Exception e) {
			}
		}
	}

	/**
	 * ����P = ([(P �C M) R + B] �C (Z + X))/n + 1
	 * @param results
	 */
	private void cacuPara_P(List<MalaysiaVO_PCB> results) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * ���㲿��н����Ŀ,��"P = [��(Y �C K*) + (Y1 �C K1*) + [(Y2 �C K2*) n] + (Yt �C Kt*)**] �C 
	 * [D + S + DU + SU +QC + (��LP + LP1)]"
	 * @param results
	 * @param context 
	 * @throws DAOException 
	 */
	private ConcurrentHashMap<String, UFDouble> cacuOtherPCBItem(MalaysiaVO_PCB[] results, WaLoginContext context) throws DAOException {
		//����n, y2, k2, D, S, DU, SU, QC, LP1
		String period = context.getCperiod();
		String year = context.getCyear();
		ConcurrentHashMap<String, UFDouble> wacacumap = new ConcurrentHashMap<String, UFDouble>();
		//�̶�����
		Map<String, UFDouble> stableParas = this.queryStablePara();
		//˰�ʶ��ձ�
		MalaysiaVO_PCB_Rate[] raterange = this.queryPCBRateRanage();
			
		//ʣ���·�
		Integer n = 12 - Integer.valueOf(period);
		for(MalaysiaVO_PCB vo : results) {
			if(MalaysiaVO_PCB.MY_RESIDENT.equals(my_pcbcategoryandgroup.get(vo.getPcbcategory()))) {
				this.calResident(vo, wacacumap, stableParas, raterange, period, year, n);
			} else if(MalaysiaVO_PCB.MY_NONRESIDENT.equals(my_pcbcategoryandgroup.get(vo.getPcbcategory()))) {
				this.calNonResident(vo, wacacumap, stableParas, raterange);
			}
			
			
		}
		return wacacumap;
		
	}
	
	/**
	 * Non-Resident
	 * @param vo
	 * @param wacacumap
	 * @param stableParas
	 * @param raterange
	 */
	private void calNonResident(MalaysiaVO_PCB vo,
			ConcurrentHashMap<String, UFDouble> wacacumap,
			Map<String, UFDouble> stableParas, MalaysiaVO_PCB_Rate[] raterange) {
		UFDouble basicslary = vo.getY1();//f_198Ӧ˰����
		//˰�ʷ���
		MalaysiaVO_PCB_Rate rangevo = this.matchPcbRateAndRange(null, raterange, vo);
		UFDouble p = UFDoubleUtils.multiply(basicslary, UFDoubleUtils.div(rangevo.getPcb_rate(), new UFDouble(100)));
		wacacumap.put(vo.getPk_cacu_data(), this.getPrecisson(p));
		
	}
	/**
	 * 
	 * @param vo
	 * @param raterange 
	 * @param stableParas 
	 * @param wacacumap 
	 * @param year 
	 * @param period 
	 * @param n 
	 */
	private void calResident(MalaysiaVO_PCB vo, ConcurrentHashMap<String, UFDouble> wacacumap, 
			Map<String, UFDouble> stableParas, MalaysiaVO_PCB_Rate[] raterange, String period, String year, Integer n) {
		//Y2: ��Y1Ϊ�����·ݵ�Ԥ������
		UFDouble y1 = vo.getY1();//f_198Ӧ˰����
		UFDouble y2 = y1;
		//���� D+S+DU+SU+QC+(LP_LP1)
		UFDouble d = stableParas.get("D");
		UFDouble kt = vo.getKt();
		UFDouble s = UFDouble.ZERO_DBL;
		UFDouble du = UFDouble.ZERO_DBL;
		UFDouble su = UFDouble.ZERO_DBL;
		UFDouble qc = UFDouble.ZERO_DBL;
		UFDouble y = UFDouble.ZERO_DBL;
		UFDouble k = UFDouble.ZERO_DBL;
		UFDouble k1 = vo.getK1();
		UFDouble k2 = UFDouble.ZERO_DBL;
		UFDouble lp = UFDouble.ZERO_DBL;
		//S
		if(!vo.getIsspouseworking().booleanValue()) {
			s = stableParas.get("S");
		}
		
		//DU
		if(vo.getIsdisable().booleanValue()) {
			du = stableParas.get("DU");
		}
		//SU
		if(vo.getIssponsedisable() != null && vo.getIssponsedisable().booleanValue()) {
			su = stableParas.get("SU");
		}
		//QC
		qc = vo.getChildren() == null ? UFDouble.ZERO_DBL : UFDoubleUtils.multiply(vo.getChildren(), stableParas.get("QC"));
		
		//Y,K,LP
		Boolean iscurrenroll = compareBeginDate(vo.getBegindate(), period, year);
		if(iscurrenroll) {
			y = UFDoubleUtils.add(vo.getY(), vo.getTotalpayable());
			k = UFDoubleUtils.add(vo.getK(), vo.getTotalepf());
			lp = UFDoubleUtils.add(vo.getLp(), vo.getTotaltax());
		} else {
			y = vo.getY();
			k = vo.getK();
			lp = vo.getLp();
		}
		//K2
		k2 = this.getK2(stableParas.get("TQ"),k, k1, kt, n);
		//����P
		UFDouble P1 = UFDoubleUtils.add(UFDoubleUtils.sub(y, k), 
				UFDoubleUtils.sub(y1, k1), 
				UFDoubleUtils.multiply(UFDoubleUtils.sub(y2, k2), new UFDouble(n)),
				UFDoubleUtils.sub(vo.getYt(), kt));
		UFDouble P2 = UFDoubleUtils.add(d, s, du, su, qc, lp, vo.getLp1());
		UFDouble P = UFDoubleUtils.sub(P1, P2);
		
		//����P ȷ��˰�ʣ� ����۳����� �������࣬ ZAKAT�õ�����˰��
		MalaysiaVO_PCB_Rate pabrange = this.matchPcbRateAndRange(P, raterange, vo);
		//MTD= ([(P �C M) R + B] �C (Z + X))/n + 1
		//����Z,X
		UFDouble z = UFDouble.ZERO_DBL;
		UFDouble x = UFDouble.ZERO_DBL;
		if(iscurrenroll) {
			z = UFDoubleUtils.add(vo.getZ(), vo.getTotalpcb());
			x = UFDoubleUtils.add(vo.getX(), vo.getTotalpcb());
		} else {
			z = vo.getZ();
			x = vo.getX();
		}
		UFDouble tempmtd = UFDoubleUtils.sub(UFDoubleUtils.add(UFDoubleUtils.multiply(
				UFDoubleUtils.sub(P,  pabrange.getPcb_m()), UFDoubleUtils.div(pabrange.getPcb_rate(),new UFDouble(100))), pabrange.getPcb_category1_3()), 
				UFDoubleUtils.add(z, x));
		UFDouble mtd = UFDoubleUtils.div(tempmtd, new UFDouble(n + 1));
		//����Net MTD: MTD - zakat
		UFDouble netmtd = UFDoubleUtils.sub(mtd, z);
		if(UFDoubleUtils.isLessThan(netmtd, UFDouble.ZERO_DBL) && 
				(UFDouble.ZERO_DBL.equals(vo.getYt()) || vo.getYt() == null)) {
			wacacumap.put(vo.getPk_cacu_data(), this.getPrecisson(UFDouble.ZERO_DBL));
			return;
		} else if(UFDoubleUtils.isLessThan(netmtd, UFDouble.ZERO_DBL) &&
				UFDoubleUtils.isGreaterThan(vo.getYt(), UFDouble.ZERO_DBL)) {
			//TODO
			netmtd = mtd;
		} else if((UFDoubleUtils.isGreaterThan(netmtd, UFDouble.ZERO_DBL) && UFDouble.ZERO_DBL.equals(vo.getYt())
				|| UFDoubleUtils.isEqual(netmtd, UFDouble.ZERO_DBL) && UFDouble.ZERO_DBL.equals(vo.getYt()))) {
			
			wacacumap.put(vo.getPk_cacu_data(), this.getPrecisson(netmtd));
			return;
		} else if(UFDoubleUtils.isGreaterThan(netmtd, UFDouble.ZERO_DBL) && UFDoubleUtils.isGreaterThan(vo.getYt(), UFDouble.ZERO_DBL)) {
			//TODO
		}
		
		//����additional remuneration : X + [Step [2.13] x (n + 1)]
		UFDouble totalmonth_decu = UFDoubleUtils.add(x, tempmtd);
		UFDouble totalyear_decu = UFDoubleUtils.add(UFDoubleUtils.multiply(UFDoubleUtils.sub(P, pabrange.getPcb_m()), pabrange.getPcb_rate()),
				pabrange.getPcb_category1_3());
		UFDouble currtax = this.getCurrTax(totalyear_decu, totalmonth_decu, z);
		
		//last
		UFDouble pcb = UFDoubleUtils.sub(UFDoubleUtils.add(netmtd, currtax), z);
		//TODO ����δ����
		
		
		wacacumap.put(vo.getPk_cacu_data(), this.getPrecisson(pcb));
		
	}
	/**
	 * Malaysia PCB����λ��ʽ�Ƚ����� 
	 * 1,2,3,4�ֽ�λΪ5    6,7,8,9��λΪ10
	 * ��2.22 Ϊ2.25,  2.27Ϊ2.30
	 * @param context
	 * @return
	 */
	public UFDouble getPrecisson(UFDouble value) {
		//������н�ʷ�����Ŀ����λ��ʽ���̶�δ�˷���
		return UFDoubleScaleUtils.setScale(value, new UFDouble(0.05), RoundingMode.HALF_UP);
	}

	/**
	 * 
	 * @param totalyear_decu
	 * @param totalmonth_decu
	 * @param z
	 * @return
	 */
	private UFDouble getCurrTax(UFDouble totalyear_decu,
			UFDouble totalmonth_decu, UFDouble z) {
		UFDouble currtax = UFDoubleUtils.add(UFDoubleUtils.add(totalyear_decu, totalmonth_decu), z);
		if(UFDoubleUtils.isLessThan(currtax, new UFDouble(10))) {
			return new UFDouble(10);
		} else {
			return currtax;
		}
	}

	/**
	 * ����P ȷ��rate�ȵ�
	 * @param p
	 * @param vo 
	 * @param rate range
	 */
	private MalaysiaVO_PCB_Rate matchPcbRateAndRange(UFDouble p, MalaysiaVO_PCB_Rate[] raterange, MalaysiaVO_PCB vo) {
		for(MalaysiaVO_PCB_Rate range : raterange) {
			if(range.getLower_limit() != null && 
					MalaysiaVO_PCB.MY_RESIDENT.equals(my_pcbcategoryandgroup.get(vo.getPcbcategory())) && //Resident
					UFDoubleUtils.isGreaterThan(p, range.getLower_limit()) &&
					UFDoubleUtils.isLessThan(p, range.getUpper_limit())) {
				return range;
			} else if(p == null && MalaysiaVO_PCB.MY_NONRESIDENT.equals(my_pcbcategoryandgroup.get(vo.getPcbcategory()))) {
				return range;
			}
		}
		return new MalaysiaVO_PCB_Rate();
	}

	/**
	 * PCB Rate range ˰�ʶ��ձ�
	 * @throws DAOException 
	 */
	private MalaysiaVO_PCB_Rate[] queryPCBRateRanage() throws DAOException {
		List<MalaysiaVO_PCB_Rate> results = (List<MalaysiaVO_PCB_Rate>) dao.retrieveAll(MalaysiaVO_PCB_Rate.class);
		return results.toArray(new MalaysiaVO_PCB_Rate[0]);
	}

	/**
	 * �ж�K1�����湫ʽ�����ֵ�ĸ�С���ĸ�Сȡ�ĸ�  
	 * @param ufDouble
	 * @param k
	 * @param k1
	 * @param kt
	 * @param n
	 */
	private UFDouble getK2(UFDouble qt, UFDouble k, UFDouble k1, UFDouble kt,
			Integer n) {
		UFDouble uf1 = UFDoubleUtils.sub(qt, UFDoubleUtils.div(UFDoubleUtils.add(k, k1, kt), new UFDouble(n)));
		UFDouble uf2 = k1;
		return UFDoubleUtils.isGreaterThan(uf1, uf2) ? uf2 : uf1;
		
	}

	/**
	 * �ж���ְ�����Ƿ���ڱ����һ��һ��
	 * @param begindate
	 * @param period
	 * @param year
	 * @return
	 */
	private boolean compareBeginDate(UFDate begindate, String period,
			String year) {
		if(begindate != null) {
			int enrollyear = begindate.getYear();
			if(enrollyear == Integer.valueOf(year)) {
				int enrollmonth = begindate.getMonth();
				if(enrollmonth > 1) {
					return true;
				} else {
					int enrollday = begindate.getDay();
					if(enrollday > 1) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * ��ѯ�̶���������Individual Deductions��Spouse Deductions�ȵ�
	 * @throws DAOException 
	 */
	private Map<String, UFDouble> queryStablePara() throws DAOException {
		Map<String, UFDouble> paramap = new HashMap<String, UFDouble>();
		List<MalaysiaVO_PCB_para> paras = (List<MalaysiaVO_PCB_para>) dao.retrieveAll(MalaysiaVO_PCB_para.class);
		for(MalaysiaVO_PCB_para para : paras) {
			paramap.put(para.getCode(), para.getValue());
		}
		return paramap;
	}

	private List<MalaysiaVO_PCB> queryPCBKnownItems(
			MalaysiaTaxFormulaVO malaysiaFormulaVO, WaLoginContext context) {
		List<MalaysiaVO_PCB> results = null;
		String condition = " pk_wa_class = ? and creator = ?";
		SQLParameter parameter = new SQLParameter();
		parameter.addParam(context.getPk_wa_class());
		parameter.addParam(context.getWaLoginVO().getCreator());
		try {
			results = (List<MalaysiaVO_PCB>) dao.retrieveByClause(MalaysiaVO_PCB.class, condition, parameter);
		} catch (Exception e) {
			ExceptionUtils.wrapException(e.getMessage(), e);
		}
		return results;
	}

	private List<MalaysiaVO_PCB> constructVO(
			List<Map<String, Object>> resultslist) {
		List<MalaysiaVO_PCB> pcbs = new ArrayList<MalaysiaVO_PCB>();
		for(Map<String, Object> map : resultslist) {
			if(map != null) {
				for(Map.Entry<String, Object> entry : map.entrySet()) {
					MalaysiaVO_PCB pcb = new MalaysiaVO_PCB();
					pcb.setY1(new UFDouble(entry.getKey()));
				}
			}
		}
		return null;
	}

	@Override
	public void excute(Object arguments, WaLoginContext context)
			throws BusinessException {
		// TODO Auto-generated method stub
		
	}

}
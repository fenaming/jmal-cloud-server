package com.jmal.clouddisk.model.rbac;

import com.jmal.clouddisk.model.CategoryDTO;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @Description 菜单传输对象
 * @blame jmal
 * @Date 2021/1/7 8:53 下午
 */
@Data
@Valid
@ApiModel
public class MenuDTO {
    /***
     * 主键
     */
    String id;
    @ApiModelProperty(name = "parentId", value = "父级菜单Id")
    String parentId;
    @NotNull(message = "菜单名称不能为空")
    @ApiModelProperty(name = "name", value = "菜单名称", required = true)
    String name;
    @ApiModelProperty(name = "authority", value = "权限标识")
    String authority;
    @ApiModelProperty(name = "path", value = "路由地址")
    String path;
    @ApiModelProperty(name = "component", value = "组件路径")
    String component;
    @ApiModelProperty(name = "icon", value = "菜单图标")
    String icon;
    @NotNull(message = "排序号不能为空")
    @ApiModelProperty(name = "sortNumber", value = "排序号", required = true)
    Integer sortNumber;
    @NotNull(message = "菜单类型不能为空")
    @ApiModelProperty(name = "menuType", value = "菜单类型 0:菜单，1:按钮", required = true)
    Integer menuType;
    @NotNull(message = "是否隐藏不能为空")
    @ApiModelProperty(name = "hide", value = "是否隐藏", required = true)
    Boolean hide;
    /***
     * 子菜单
     */
    @ApiModelProperty(hidden = true)
    private List<MenuDTO> children;
    /***
     * 创建时间
     */
    @ApiModelProperty(hidden = true)
    LocalDateTime createTime;
    /***
     * 修改时间
     */
    @ApiModelProperty(hidden = true)
    LocalDateTime updateTime;
}
